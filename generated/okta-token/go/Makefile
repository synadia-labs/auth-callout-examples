.PHONY: build run-service run-client clean

BINARY := authcallout-okta-token

build:
	go mod tidy && go build -o $(BINARY) .

run-service: build
	./$(BINARY) service

run-client: build
	./$(BINARY) client $(TOKEN)

clean:
	rm -f $(BINARY)
