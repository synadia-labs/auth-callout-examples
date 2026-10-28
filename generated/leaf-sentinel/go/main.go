package main

import (
	"fmt"
	"os"
)

func main() {
	mode := "service"
	if len(os.Args) > 1 {
		mode = os.Args[1]
	}
	switch mode {
	case "service":
		runService()
	case "client":
		// client <nkey-seed> [label]
		if len(os.Args) < 3 {
			fmt.Fprintf(os.Stderr, "Usage: %s client <nkey-seed> [label]\n", os.Args[0])
			os.Exit(1)
		}
		label := "test-client"
		if len(os.Args) >= 4 {
			label = os.Args[3]
		}
		runClient(os.Args[2], label)
	default:
		fmt.Fprintf(os.Stderr, "Usage: %s service|client\n", os.Args[0])
		os.Exit(1)
	}
}
