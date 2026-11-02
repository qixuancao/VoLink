package main

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"testing"
	"time"
)

func TestFraming(t *testing.T) {
	for _, size := range []int{1, 1200, maxDatagram} {
		want := bytes.Repeat([]byte{0x41}, size)
		var b bytes.Buffer
		if err := writeFrame(&b, want); err != nil {
			t.Fatal(err)
		}
		got, err := readFrame(&b)
		if err != nil || !bytes.Equal(got, want) {
			t.Fatalf("size %d: %v", size, err)
		}
	}
	for _, size := range []uint32{0, maxDatagram + 1, ^uint32(0)} {
		var b bytes.Buffer
		binary.Write(&b, binary.BigEndian, size)
		if _, err := readFrame(&b); err == nil {
			t.Fatalf("accepted invalid size %d", size)
		}
	}
	for _, data := range [][]byte{{0}, {0, 0, 0, 5, 1, 2}} {
		if _, err := readFrame(bytes.NewReader(data)); err == nil {
			t.Fatal("accepted truncated frame")
		}
	}
}

type brokenWriter struct{}

func (brokenWriter) Write(b []byte) (int, error) { return 0, io.ErrClosedPipe }

func TestReplyFailureStopsIdleInput(t *testing.T) {
	server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	conn, err := net.DialUDP("udp4", nil, server.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	inputR, inputW := io.Pipe()
	defer inputW.Close()
	done := make(chan error, 1)
	go func() { done <- run(inputR, brokenWriter{}, conn, 0) }()
	go func() {
		b := make([]byte, 100)
		_, peer, err := server.ReadFromUDP(b)
		if err == nil {
			server.WriteToUDP([]byte("reply"), peer)
		}
	}()
	if err := writeFrame(inputW, []byte("voice")); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if !errors.Is(err, io.ErrClosedPipe) {
			t.Fatalf("unexpected error: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("reply failure left bridge hanging")
	}
}

func TestFullDuplexAndEOF(t *testing.T) {
	server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	conn, err := net.DialUDP("udp4", nil, server.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	inputR, inputW := io.Pipe()
	outputR, outputW := io.Pipe()
	defer outputR.Close()
	defer outputW.Close()
	done := make(chan error, 1)
	go func() { done <- run(inputR, outputW, conn, 0) }()
	go func() {
		b := make([]byte, 1200)
		n, peer, e := server.ReadFromUDP(b)
		if e == nil {
			server.WriteToUDP(append([]byte("reply:"), b[:n]...), peer)
		}
	}()
	if err := writeFrame(inputW, []byte("voice")); err != nil {
		t.Fatal(err)
	}
	received := make(chan []byte, 1)
	go func() { b, _ := readFrame(outputR); received <- b }()
	select {
	case b := <-received:
		if string(b) != "reply:voice" {
			t.Fatalf("got %q", b)
		}
	case <-time.After(time.Second):
		t.Fatal("duplex timeout")
	}
	inputW.Close()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("EOF did not stop bridge")
	}
}

func TestHeartbeatKeepsSessionAliveAndTimeoutStopsIt(t *testing.T) {
	server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	conn, err := net.DialUDP("udp4", nil, server.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	inputR, inputW := io.Pipe()
	outputR, outputW := io.Pipe()
	defer inputW.Close()
	defer outputR.Close()
	defer outputW.Close()
	done := make(chan error, 1)
	go func() { done <- run(inputR, outputW, conn, 200*time.Millisecond) }()
	if packet, err := readFrameMode(outputR, true); err != nil || len(packet) != 0 {
		t.Fatalf("heartbeat negotiation: %v, %v", packet, err)
	}
	for i := 0; i < 4; i++ {
		time.Sleep(80 * time.Millisecond)
		if err := writeAll(inputW, []byte{0, 0, 0, 0}); err != nil {
			t.Fatal(err)
		}
		select {
		case err := <-done:
			t.Fatalf("live heartbeat timed out: %v", err)
		default:
		}
	}
	select {
	case err := <-done:
		if err == nil || err.Error() != "client heartbeat timed out" {
			t.Fatalf("unexpected timeout result: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("missing heartbeat did not stop bridge")
	}
}
