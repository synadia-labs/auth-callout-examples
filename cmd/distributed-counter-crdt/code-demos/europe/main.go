package main

import (
	"context"
	"embed"
	"encoding/json"
	"log"
	"net/http"
	"net/url"
	"os"
	"time"

	natssrv "github.com/nats-io/nats-server/v2/server"
	"github.com/nats-io/nats.go"
	"github.com/nats-io/nats.go/jetstream"
	"github.com/starfederation/datastar-go/datastar"
)

//go:embed static
var staticFiles embed.FS

const (
	signalName = "hits"
	subject    = "count.europe.hits"
	streamName = "COUNTER_EUROPE"
)

type app struct {
	js jetstream.JetStream
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func main() {
	storeDir := os.Getenv("STORE_DIR")
	if storeDir == "" {
		storeDir = "/data"
	}

	leafRemote := os.Getenv("LEAF_REMOTE_URL")
	leafNode := natssrv.LeafNodeOpts{Port: 7422}
	if leafRemote != "" {
		u, err := url.Parse(leafRemote)
		if err != nil {
			log.Fatalf("parse LEAF_REMOTE_URL: %v", err)
		}
		leafNode.Remotes = []*natssrv.RemoteLeafOpts{{URLs: []*url.URL{u}}}
	}

	opts := &natssrv.Options{
		ServerName:      "europe",
		JetStream:       true,
		JetStreamDomain: "europe",
		StoreDir:        storeDir,
		HTTPPort:        8222,
		LeafNode:        leafNode,
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

	if _, err := nc.Subscribe(subject, func(msg *nats.Msg) {
		log.Printf("[msg] subject=%s incr=%s", msg.Subject, msg.Header.Get("Nats-Incr"))
	}); err != nil {
		log.Fatalf("subscribe: %v", err)
	}

	js, err := jetstream.New(nc)
	if err != nil {
		log.Fatalf("jetstream: %v", err)
	}

	cfg := jetstream.StreamConfig{
		Name:            "COUNTER_EUROPE",
		Subjects:        []string{subject},
		AllowMsgCounter: true,
		AllowDirect:     true,
		Storage:         jetstream.FileStorage,
		Sources: []*jetstream.StreamSource{
			{
				Name:   "COUNTER_SPAIN",
				Domain: "spain",
				SubjectTransforms: []jetstream.SubjectTransformConfig{{
					Source:      "count.spain.hits",
					Destination: subject,
				}},
			},
			{
				Name:   "COUNTER_FRANCE",
				Domain: "france",
				SubjectTransforms: []jetstream.SubjectTransformConfig{{
					Source:      "count.france.hits",
					Destination: subject,
				}},
			},
			{
				Name:   "COUNTER_ENGLAND",
				Domain: "england",
				SubjectTransforms: []jetstream.SubjectTransformConfig{{
					Source:      "count.england.hits",
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
	log.Println("stream COUNTER_EUROPE ready")

	subRegions := []struct {
		localStream  string
		sourceStream string
		domain       string
		subject      string
	}{
		{"COUNTER_FRANCE_VIEW", "COUNTER_FRANCE", "france", "count.france.hits"},
		{"COUNTER_SPAIN_VIEW", "COUNTER_SPAIN", "spain", "count.spain.hits"},
		{"COUNTER_ENGLAND_VIEW", "COUNTER_ENGLAND", "england", "count.england.hits"},
	}
	for _, sr := range subRegions {
		srcfg := jetstream.StreamConfig{
			Name:        sr.localStream,
			AllowDirect: true,
			Storage:     jetstream.FileStorage,
			Mirror: &jetstream.StreamSource{
				Name:   sr.sourceStream,
				Domain: sr.domain,
			},
		}
		deadline := time.Now().Add(15 * time.Second)
		for time.Now().Before(deadline) {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			_, err = js.CreateOrUpdateStream(ctx, srcfg)
			cancel()
			if err == nil {
				break
			}
			log.Printf("create view stream %s (retrying): %v", sr.localStream, err)
			time.Sleep(time.Second)
		}
		if err != nil {
			log.Printf("view stream %s unavailable: %v", sr.localStream, err)
			continue
		}
		log.Printf("view stream %s ready", sr.localStream)
	}

	a := &app{js: js}

	indexData, _ := staticFiles.ReadFile("static/index.html")

	mux := http.NewServeMux()
	mux.HandleFunc("GET /", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		w.Write(indexData)
	})
	mux.HandleFunc("GET /counters", a.handleCounters)

	addr := getEnv("HTTP_ADDR", ":8080")
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

