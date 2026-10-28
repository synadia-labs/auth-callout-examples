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
		if len(os.Args) < 4 {
			fmt.Fprintf(os.Stderr, "Usage: %s client <username> <password>\n", os.Args[0])
			os.Exit(1)
		}
		runClient(os.Args[2], os.Args[3])
	default:
		fmt.Fprintf(os.Stderr, "Usage: %s service|client\n", os.Args[0])
		os.Exit(1)
	}
}
