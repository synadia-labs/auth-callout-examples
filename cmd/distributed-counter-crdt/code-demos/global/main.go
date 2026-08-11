package main

import (
	"context"
	"embed"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	natssrv "github.com/nats-io/nats-server/v2/server"
	"github.com/nats-io/nats.go"
	"github.com/nats-io/nats.go/jetstream"
	"github.com/starfederation/datastar-go/datastar"
)

//go:embed static
var staticFiles embed.FS

const (
	signalName = "globalHits"
	subject    = "count.global.hits"
	streamName = "COUNTER_GLOBAL"
)

type regionDef struct {
	localStream  string
	sourceStream string
	domain       string
	subject      string
	signal       string
}

var regions = []regionDef{
	{"COUNTER_AMERICA_VIEW", "COUNTER_AMERICA", "america", "count.america.hits", "americaHits"},
	{"COUNTER_ASIA_VIEW", "COUNTER_ASIA", "asia", "count.asia.hits", "asiaHits"},
	{"COUNTER_EUROPE_VIEW", "COUNTER_EUROPE", "europe", "count.europe.hits", "europeHits"},
	{"COUNTER_FRANCE_GLOBAL_VIEW", "COUNTER_FRANCE_VIEW", "europe", "count.france.hits", "franceHits"},
	{"COUNTER_SPAIN_GLOBAL_VIEW", "COUNTER_SPAIN_VIEW", "europe", "count.spain.hits", "spainHits"},
	{"COUNTER_ENGLAND_GLOBAL_VIEW", "COUNTER_ENGLAND_VIEW", "europe", "count.england.hits", "englandHits"},
}

type app struct {
	js          jetstream.JetStream
	viewStreams map[string]jetstream.Stream
}

func main() {
	storeDir := os.Getenv("STORE_DIR")
	if storeDir == "" {
		storeDir = "/data"
	}

	opts := &natssrv.Options{
		ServerName:      "global",
		JetStream:       true,
		JetStreamDomain: "global",
		StoreDir:        storeDir,
		HTTPPort:        8222,
		LeafNode: natssrv.LeafNodeOpts{
			Port: 7422,
		},
	}

	ns, err := natssrv.NewServer(opts)
	if err != nil {
		log.Fatalf("nats server: %v", err)
	}
	ns.Start()
	if !ns.ReadyForConnections(10 * time.Second) {
		log.Fatal("nats server not ready after 10s")
	}
	log.Printf("embedded nats server ready: %s", ns.ClientURL())

	var nc *nats.Conn
	for {
		nc, err = nats.Connect(ns.ClientURL())
		if err == nil {
			break
		}
		log.Printf("connect (retrying): %v", err)
		time.Sleep(time.Second)
	}
	defer nc.Close()

	if _, err := nc.Subscribe("count.*.hits", func(msg *nats.Msg) {
		parts := strings.Split(msg.Subject, ".")
		log.Printf("[msg] region=%-8s incr=%s", parts[1], msg.Header.Get("Nats-Incr"))
	}); err != nil {
		log.Fatalf("subscribe: %v", err)
	}

	js, err := jetstream.New(nc)
	if err != nil {
		log.Fatalf("jetstream: %v", err)
	}

	cfg := jetstream.StreamConfig{
		Name:            "COUNTER_GLOBAL",
		Subjects:        []string{subject},
		AllowMsgCounter: true,
		AllowDirect:     true,
		Storage:         jetstream.FileStorage,
		Sources: []*jetstream.StreamSource{
			{
				Name:   "COUNTER_ASIA",
				Domain: "asia",
				SubjectTransforms: []jetstream.SubjectTransformConfig{{
					Source:      "count.asia.hits",
					Destination: subject,
				}},
			},
			{
				Name:   "COUNTER_AMERICA",
				Domain: "america",
				SubjectTransforms: []jetstream.SubjectTransformConfig{{
					Source:      "count.america.hits",
					Destination: subject,
				}},
			},
			{
				Name:   "COUNTER_EUROPE",
				Domain: "europe",
				SubjectTransforms: []jetstream.SubjectTransformConfig{{
					Source:      "count.europe.hits",
					Destination: subject,
				}},
			},
		},
	}
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		_, err = js.CreateOrUpdateStream(ctx, cfg)
		cancel()
		if err == nil {
			break
		}
		log.Printf("create stream (retrying): %v", err)
		time.Sleep(time.Second)
	}
	log.Println("stream COUNTER_GLOBAL ready")

	go func() {
		cons, err := js.OrderedConsumer(context.Background(), streamName, jetstream.OrderedConsumerConfig{
			FilterSubjects: []string{subject},
			DeliverPolicy:  jetstream.DeliverNewPolicy,
		})
		if err != nil {
			log.Printf("source logger consumer: %v", err)
			return
		}
		iter, err := cons.Messages()
		if err != nil {
			log.Printf("source logger iter: %v", err)
			return
		}
		for {
			msg, err := iter.Next()
			if err != nil {
				return
			}
			msg.Ack()
			val := valFromMsg(msg.Data())
			if src := msg.Headers().Get("Nats-Stream-Source"); src != "" {
				name, _, _ := strings.Cut(strings.SplitN(src, " ", 2)[0], ":")
				log.Printf("[source] stream=%-20s val=%s", name, val)
			}
			if ncs := msg.Headers().Get("Nats-Counter-Sources"); ncs != "" {
				log.Printf("[sources] %s", ncs)
			}
		}
	}()

	viewStreams := map[string]jetstream.Stream{}
	for _, reg := range regions {
		rcfg := jetstream.StreamConfig{
			Name:        reg.localStream,
			AllowDirect: true,
			Storage:     jetstream.FileStorage,
			Mirror: &jetstream.StreamSource{
				Name:   reg.sourceStream,
				Domain: reg.domain,
			},
		}
		var vs jetstream.Stream
		deadline := time.Now().Add(15 * time.Second)
		for time.Now().Before(deadline) {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			vs, err = js.CreateOrUpdateStream(ctx, rcfg)
			cancel()
			if err == nil {
				break
			}
			log.Printf("create view stream %s (retrying): %v", reg.localStream, err)
			time.Sleep(time.Second)
		}
		if err != nil {
			log.Printf("view stream %s unavailable, will show 0: %v", reg.localStream, err)
			continue
		}
		log.Printf("view stream %s ready", reg.localStream)
		viewStreams[reg.signal] = vs
	}

	a := &app{js: js, viewStreams: viewStreams}

	indexData, _ := staticFiles.ReadFile("static/index.html")

	mux := http.NewServeMux()
	mux.HandleFunc("GET /", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		w.Write(indexData)
	})
	mux.HandleFunc("GET /counters", a.handleCounters)
	mux.HandleFunc("GET /breakdown", a.handleBreakdown)

	addr := os.Getenv("HTTP_ADDR")
	if addr == "" {
		addr = ":8080"
	}
	log.Printf("listening %s", addr)
	log.Fatal(http.ListenAndServe(addr, mux))
}

func valFromMsg(data []byte) string {
	var v struct {
		Val string `json:"val"`
	}
	if err := json.Unmarshal(data, &v); err != nil || v.Val == "" {
		return "0"
	}
	return v.Val
}

func (a *app) handleCounters(w http.ResponseWriter, r *http.Request) {
	sse := datastar.NewSSE(w, r)
	ctx := r.Context()

	cons, err := a.js.OrderedConsumer(ctx, streamName, jetstream.OrderedConsumerConfig{
		FilterSubjects: []string{subject},
		DeliverPolicy:  jetstream.DeliverLastPolicy,
	})
	if err != nil {
		return
	}
	iter, err := cons.Messages()
	if err != nil {
		return
	}
	defer iter.Stop()

	for {
		msg, err := iter.Next()
		if err != nil {
			return
		}
		msg.Ack()
		_ = sse.MarshalAndPatchSignals(map[string]any{signalName: valFromMsg(msg.Data())})
	}
}

func (a *app) handleBreakdown(w http.ResponseWriter, r *http.Request) {
	sse := datastar.NewSSE(w, r)
	ctx := r.Context()

	type update struct{ signal, val string }
	ch := make(chan update, len(regions))

	for _, reg := range regions {
		if _, ok := a.viewStreams[reg.signal]; !ok {
			continue
		}
		go func() {
			cons, err := a.js.OrderedConsumer(ctx, reg.localStream, jetstream.OrderedConsumerConfig{
				DeliverPolicy: jetstream.DeliverLastPolicy,
			})
			if err != nil {
				return
			}
			iter, err := cons.Messages()
			if err != nil {
				return
			}
			defer iter.Stop()
			for {
				msg, err := iter.Next()
				if err != nil {
					return
				}
				msg.Ack()
				ch <- update{reg.signal, valFromMsg(msg.Data())}
			}
		}()
	}

	for {
		select {
		case <-ctx.Done():
			return
		case u := <-ch:
			_ = sse.MarshalAndPatchSignals(map[string]any{u.signal: u.val})
		}
	}
}

