// Delayed Message Scheduling Demo
//
// This program demonstrates NATS JetStream's Message Scheduler by:
// 1. Creating a schedule-enabled stream
// 2. Subscribing to the target subject to observe deliveries
// 3. Publishing a single delayed message (@at) and waiting for it
// 4. Publishing a recurring schedule (@every) and collecting several deliveries
// 5. Subject sampling — periodically re-publishing the latest sensor reading
// 6. Cancelling schedules by purging schedule subjects
//
// Requires: nats-server 2.14+ running with JetStream enabled (nats-server -js)

package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"time"

	"github.com/nats-io/nats.go"
	"github.com/nats-io/nats.go/jetstream"
)

func main() {
	url := os.Getenv("NATS_URL")
	if url == "" {
		url = nats.DefaultURL
	}

	// Connect to NATS
	nc, err := nats.Connect(url)
	if err != nil {
		log.Fatalf("connect: %v", err)
	}
	defer nc.Close()
	fmt.Println("Connected to", nc.ConnectedUrl())

	js, err := jetstream.New(nc)
	if err != nil {
		log.Fatalf("jetstream: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	// 1. Create a stream with scheduling enabled.
	//    Target subjects (orders, sensors.sampled) must be in the same stream —
	//    the scheduler can only deliver within its own stream.
	stream, err := js.CreateOrUpdateStream(ctx, jetstream.StreamConfig{
		Name:              "SCHED_DEMO",
		Subjects:          []string{"schedules.>", "orders", "sensors.temperature", "sensors.sampled"},
		AllowMsgTTL:       true,
		AllowMsgSchedules: true,
	})
	if err != nil {
		log.Fatalf("create stream: %v", err)
	}
	fmt.Printf("Stream %q created (schedules enabled)\n\n", stream.CachedInfo().Config.Name)

	// 2. Create a pull consumer on the target subject to observe deliveries
	cons, err := js.CreateOrUpdateConsumer(ctx, "SCHED_DEMO", jetstream.ConsumerConfig{
		Name:          "demo-watcher",
		FilterSubject: "orders",
		AckPolicy:     jetstream.AckExplicitPolicy,
	})
	if err != nil {
		log.Fatalf("create consumer: %v", err)
	}

	// Get a message iterator — call Next() to pull one message at a time
	orders, err := cons.Messages()
	if err != nil {
		log.Fatalf("messages: %v", err)
	}
	defer orders.Stop()
	fmt.Println("Consumer created, watching 'orders' subject")

	// 3. One-shot delayed message — deliver once, 5 seconds from now.
	//    @at <RFC3339>  → fire at an exact time
	//    Target         → subject the message will appear on
	//    TTL            → schedule expires if undelivered within 5 minutes
	deliverAt := time.Now().UTC().Add(5 * time.Second).Format(time.RFC3339)
	msg := &nats.Msg{
		Subject: "schedules.orders.single",
		Data:    []byte(`{"order_id":"demo-001","action":"process"}`),
		Header:  nats.Header{},
	}
	msg.Header.Set("Nats-Schedule", "@at "+deliverAt)
	msg.Header.Set("Nats-Schedule-Target", "orders")
	msg.Header.Set("Nats-Schedule-TTL", "5m")

	ack, err := js.PublishMsg(ctx, msg)
	if err != nil {
		log.Fatalf("publish delayed: %v", err)
	}
	fmt.Printf("Scheduled single message (seq=%d) for delivery at %s\n", ack.Sequence, deliverAt)

	// Wait for the delayed message to arrive
	fmt.Println("Waiting for delayed message...")
	received, err := orders.Next()
	if err != nil {
		log.Fatalf("next delayed: %v", err)
	}
	scheduler := received.Headers().Get("Nats-Scheduler")
	schedNext := received.Headers().Get("Nats-Schedule-Next")
	fmt.Printf("Received delayed message: subject=%s scheduler=%s next=%s body=%s\n",
		received.Subject(), scheduler, schedNext, string(received.Data()))
	if err := received.Ack(); err != nil {
		log.Fatalf("ack delayed: %v", err)
	}
	fmt.Println()

	// 4. Recurring schedule — repeat the same message every 3 seconds.
	//    @every 3s → fire on a fixed interval
	//    TTL 1m    → the schedule auto-expires after 1 minute
	recurring := &nats.Msg{
		Subject: "schedules.orders.recurring",
		Data:    []byte(`{"action":"heartbeat"}`),
		Header:  nats.Header{},
	}
	recurring.Header.Set("Nats-Schedule", "@every 3s")
	recurring.Header.Set("Nats-Schedule-Target", "orders")
	recurring.Header.Set("Nats-Schedule-TTL", "1m")

	ack, err = js.PublishMsg(ctx, recurring)
	if err != nil {
		log.Fatalf("publish recurring: %v", err)
	}
	fmt.Printf("Published recurring schedule (seq=%d), collecting 5 deliveries...\n", ack.Sequence)

	// Collect 5 recurring messages
	for i := range 5 {
		received, err := orders.Next()
		if err != nil {
			log.Fatalf("next recurring #%d: %v", i+1, err)
		}
		schedNext := received.Headers().Get("Nats-Schedule-Next")
		fmt.Printf("  Recurring #%d: next=%s body=%s\n", i+1, schedNext, string(received.Data()))
		if err := received.Ack(); err != nil {
			log.Fatalf("ack recurring: %v", err)
		}
	}
	fmt.Println()

	// Cancel the recurring schedule before moving on
	err = stream.Purge(ctx, jetstream.WithPurgeSubject("schedules.orders.recurring"))
	if err != nil {
		log.Fatalf("purge recurring: %v", err)
	}
	fmt.Println("Recurring schedule cancelled (subject purged)")
	fmt.Println()

	// 5. Subject sampling — poll the latest value and republish it.
	//    A goroutine writes sensor readings every 1s to sensors.temperature.
	//    The scheduler grabs the latest reading every 5s (Source) and
	//    republishes it to sensors.sampled (Target). TTL 1m auto-expires.
	go func() {
		for i := 1; ; i++ {
			select {
			case <-ctx.Done():
				return
			default:
			}
			_, err := js.Publish(ctx, "sensors.temperature",
				fmt.Appendf(nil, `{"temp_c":%.1f,"reading":%d}`, 36.5+float64(i)*0.3, i))
			if err != nil {
				return
			}
			fmt.Printf("  Sensor reading #%d: temp_c=%.1f\n", i, 36.5+float64(i)*0.3)
			time.Sleep(1 * time.Second)
		}
	}()
	fmt.Println("Sensor goroutine started (publishing every 1s)")

	samplerCons, err := js.CreateOrUpdateConsumer(ctx, "SCHED_DEMO", jetstream.ConsumerConfig{
		Name:          "sampler-watcher",
		FilterSubject: "sensors.sampled",
		AckPolicy:     jetstream.AckExplicitPolicy,
	})
	if err != nil {
		log.Fatalf("create sampler consumer: %v", err)
	}

	sampled, err := samplerCons.Messages()
	if err != nil {
		log.Fatalf("messages sampler: %v", err)
	}
	defer sampled.Stop()

	sampler := &nats.Msg{
		Subject: "schedules.sensors.sample",
		Data:    nil,
		Header:  nats.Header{},
	}
	sampler.Header.Set("Nats-Schedule", "@every 5s")
	sampler.Header.Set("Nats-Schedule-Source", "sensors.temperature")
	sampler.Header.Set("Nats-Schedule-Target", "sensors.sampled")
	sampler.Header.Set("Nats-Schedule-TTL", "1m")

	ack, err = js.PublishMsg(ctx, sampler)
	if err != nil {
		log.Fatalf("publish sampler: %v", err)
	}
	fmt.Printf("Subject sampling schedule created (seq=%d), collecting 3 samples...\n", ack.Sequence)

	for i := range 3 {
		received, err := sampled.Next()
		if err != nil {
			log.Fatalf("next sampled #%d: %v", i+1, err)
		}
		fmt.Printf("  >>> SAMPLED #%d: body=%s <<<\n", i+1, string(received.Data()))
		if err := received.Ack(); err != nil {
			log.Fatalf("ack sampled: %v", err)
		}
	}

	// Cancel the sampler schedule
	err = stream.Purge(ctx, jetstream.WithPurgeSubject("schedules.sensors.sample"))
	if err != nil {
		log.Fatalf("purge sampler: %v", err)
	}
	fmt.Println("\nSubject sampling schedule cancelled")

	// Cleanup
	err = js.DeleteStream(ctx, "SCHED_DEMO")
	if err != nil {
		log.Fatalf("delete stream: %v", err)
	}
	fmt.Println("Stream deleted. Done.")
}
