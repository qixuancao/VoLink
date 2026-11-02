// SPDX-License-Identifier: GPL-3.0-or-later
// The SSH channel transports bounded, length-prefixed UDP datagrams.
package main

import (
	"encoding/binary"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"time"
)

const maxDatagram = 65507

func readFrame(r io.Reader) ([]byte, error) {
	return readFrameMode(r, false)
}

func readFrameMode(r io.Reader, allowHeartbeat bool) ([]byte, error) {
	var header [4]byte
	if _, err := io.ReadFull(r, header[:]); err != nil {
		return nil, err
	}
	size := binary.BigEndian.Uint32(header[:])
	if size == 0 && allowHeartbeat {
		return nil, nil
	}
	if size == 0 || size > maxDatagram {
		return nil, fmt.Errorf("invalid datagram size %d", size)
	}
	packet := make([]byte, int(size))
	_, err := io.ReadFull(r, packet)
	return packet, err
}

func writeAll(w io.Writer, b []byte) error {
	for len(b) != 0 {
		n, err := w.Write(b)
		if err != nil {
			return err
		}
		if n <= 0 {
			return io.ErrShortWrite
		}
		b = b[n:]
	}
	return nil
}

func writeFrame(w io.Writer, b []byte) error {
	if len(b) == 0 || len(b) > maxDatagram {
		return errors.New("invalid outgoing datagram size")
	}
	var header [4]byte
	binary.BigEndian.PutUint32(header[:], uint32(len(b)))
	if err := writeAll(w, header[:]); err != nil {
		return err
	}
	return writeAll(w, b)
}

func run(r io.Reader, w io.Writer, conn *net.UDPConn, heartbeatTimeout time.Duration) error {
	// Whichever direction fails first stops the entire session, including a
	// reply failure while SSH stdin is idle. Bounded channels prevent leaks.
	defer conn.Close()
	if closer, ok := r.(io.Closer); ok {
		defer closer.Close()
	}
	done := make(chan error, 2)
	heartbeats := make(chan struct{}, 1)
	var timer *time.Timer
	var timeout <-chan time.Time
	if heartbeatTimeout > 0 {
		// A zero-length frame negotiates heartbeats without affecting old clients.
		if err := writeAll(w, []byte{0, 0, 0, 0}); err != nil {
			return err
		}
		timer = time.NewTimer(heartbeatTimeout)
		defer timer.Stop()
		timeout = timer.C
	}
	go func() {
		b := make([]byte, maxDatagram)
		for {
			n, err := conn.Read(b)
			if err == nil {
				err = writeFrame(w, b[:n])
			}
			if err != nil {
				done <- err
				return
			}
		}
	}()
	go func() {
		for {
			packet, err := readFrameMode(r, heartbeatTimeout > 0)
			if err == nil && len(packet) == 0 {
				select {
				case heartbeats <- struct{}{}:
				default:
				}
				continue
			}
			if err == nil {
				_, err = conn.Write(packet)
			}
			if err != nil {
				done <- err
				return
			}
		}
	}()
	for {
		select {
		case err := <-done:
			if errors.Is(err, io.EOF) {
				return nil
			}
			return err
		case <-heartbeats:
			if !timer.Stop() {
				select {
				case <-timer.C:
				default:
				}
			}
			timer.Reset(heartbeatTimeout)
		case <-timeout:
			return errors.New("client heartbeat timed out")
		}
	}
}

func main() {
	stdio := flag.Bool("stdio", false, "run the SSH audio channel")
	heartbeat := flag.Bool("heartbeat", false, "negotiate and monitor client heartbeats")
	supportsHeartbeat := flag.Bool("supports-heartbeat", false, "check heartbeat protocol support")
	flag.Parse()
	if *supportsHeartbeat && flag.NArg() == 0 {
		return
	}
	if !*stdio || flag.NArg() != 0 {
		flag.Usage()
		os.Exit(2)
	}
	// Fixed loopback destination: this is never an arbitrary UDP proxy.
	conn, err := net.DialUDP("udp4", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 12001})
	if err == nil {
		var timeout time.Duration
		if *heartbeat {
			timeout = 45 * time.Second
		}
		err = run(os.Stdin, os.Stdout, conn, timeout)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "audio bridge:", err)
		os.Exit(1)
	}
}
