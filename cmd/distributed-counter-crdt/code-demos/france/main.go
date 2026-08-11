package main

import (
	"context"
	"embed"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"sync"
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
	subject    = "count.france.hits"
	streamName = "COUNTER_FRANCE"
)

type app struct {
	js          jetstream.JetStream
	stream      jetstream.Stream
	ns          *natssrv.Server
	opts        *natssrv.Options
	mu          sync.Mutex
	partitioned bool
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
	if leafRemote == "" {
		log.Fatal("LEAF_REMOTE_URL is required")
	}
	u, err := url.Parse(leafRemote)
	if err != nil {
		log.Fatalf("parse LEAF_REMOTE_URL: %v", err)
	}

	opts := &natssrv.Options{
		ServerName:      "france",
		JetStream:       true,
		JetStreamDomain: "france",
		StoreDir:        storeDir,
		HTTPPort:        8222,
		LeafNode: natssrv.LeafNodeOpts{
			Remotes: []*natssrv.RemoteLeafOpts{{URLs: []*url.URL{u}}},
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
		Name:            "COUNTER_FRANCE",
		Subjects:        []string{subject},
		AllowMsgCounter: true,
		AllowDirect:     true,
		Storage:         jetstream.FileStorage,
	}
	var stream jetstream.Stream
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		stream, err = js.CreateOrUpdateStream(ctx, cfg)
		cancel()
		if err == nil {
			break
		}
		log.Printf("create stream (retrying): %v", err)
		time.Sleep(time.Second)
	}
	log.Println("stream COUNTER_FRANCE ready")

	a := &app{js: js, stream: stream, ns: ns, opts: opts}

	indexData, _ := staticFiles.ReadFile("static/index.html")

	mux := http.NewServeMux()
	mux.HandleFunc("GET /", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		_, _ = w.Write(indexData)
	})
	mux.HandleFunc("GET /counters", a.handleCounters)
	mux.HandleFunc("POST /hit/{node}/{amount}", a.handleHit)
	mux.HandleFunc("POST /zero/{node}", a.handleZero)
	mux.HandleFunc("POST /partition", a.handlePartition)
	mux.HandleFunc("POST /rejoin", a.handleRejoin)

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

func (a *app) readVal() string {
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	msg, err := a.stream.GetLastMsgForSubject(ctx, subject)
	if err != nil {
		return "0"
	}
	return valFromMsg(msg.Data)
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
		_ = msg.Ack()
		_ = sse.MarshalAndPatchSignals(map[string]any{signalName: valFromMsg(msg.Data())})
	}
}

func (a *app) handleHit(w http.ResponseWriter, r *http.Request) {
	amount, err := strconv.Atoi(r.PathValue("amount"))
	if err != nil || amount <= 0 {
		http.Error(w, "invalid amount", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), time.Second)
	defer cancel()
	ack, err := a.js.PublishMsg(ctx, &nats.Msg{
		Subject: subject,
		Header:  nats.Header{"Nats-Incr": {fmt.Sprintf("%+d", amount)}},
	})
	if err != nil {
		log.Printf("increment: %v", err)
		http.Error(w, "increment failed", http.StatusInternalServerError)
		return
	}
	log.Printf("hit: node=%s delta=%d val=%s", r.PathValue("node"), amount, ack.Value)
	sse := datastar.NewSSE(w, r)
	_ = sse.MarshalAndPatchSignals(map[string]any{signalName: ack.Value})
}

func (a *app) handlePartition(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	defer a.mu.Unlock()
	newOpts := *a.opts
	newOpts.LeafNode = natssrv.LeafNodeOpts{
		Remotes: []*natssrv.RemoteLeafOpts{{URLs: a.opts.LeafNode.Remotes[0].URLs, Disabled: true}},
	}
	if err := a.ns.ReloadOptions(&newOpts); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	a.partitioned = true
	w.WriteHeader(http.StatusNoContent)
}

func (a *app) handleRejoin(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	defer a.mu.Unlock()
	newOpts := *a.opts
	newOpts.LeafNode = natssrv.LeafNodeOpts{
		Remotes: []*natssrv.RemoteLeafOpts{{URLs: a.opts.LeafNode.Remotes[0].URLs, Disabled: false}},
	}
	if err := a.ns.ReloadOptions(&newOpts); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	a.partitioned = false
	w.WriteHeader(http.StatusNoContent)
}

func (a *app) handleZero(w http.ResponseWriter, r *http.Request) {
	current, err := strconv.Atoi(a.readVal())
	if err != nil || current == 0 {
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), time.Second)
	defer cancel()
	ack, err := a.js.PublishMsg(ctx, &nats.Msg{
		Subject: subject,
		Header:  nats.Header{"Nats-Incr": {fmt.Sprintf("%+d", -current)}},
	})
	if err != nil {
		log.Printf("zero: %v", err)
		http.Error(w, "zero failed", http.StatusInternalServerError)
		return
	}
	log.Printf("zero: node=%s delta=%d val=%s", r.PathValue("node"), -current, ack.Value)
	sse := datastar.NewSSE(w, r)
	_ = sse.MarshalAndPatchSignals(map[string]any{signalName: ack.Value})
}
