package events

import (
	"sync"
	"time"
)

type Event struct {
	Type      string `json:"type"`
	SourceID  string `json:"source_id,omitempty"`
	TaskID    string `json:"task_id,omitempty"`
	Count     int    `json:"count,omitempty"`
	Message   string `json:"message,omitempty"`
	Timestamp int64  `json:"timestamp"`
}

type Broker struct {
	mu          sync.RWMutex
	subscribers map[uint64]chan Event
	nextID      uint64
}

func NewBroker() *Broker {
	return &Broker{
		subscribers: make(map[uint64]chan Event),
	}
}

func (broker *Broker) Publish(event Event) {
	if broker == nil {
		return
	}
	if event.Timestamp == 0 {
		event.Timestamp = time.Now().Unix()
	}
	broker.mu.RLock()
	defer broker.mu.RUnlock()
	for _, ch := range broker.subscribers {
		select {
		case ch <- event:
		default:
		}
	}
}

func (broker *Broker) Subscribe(buffer int) (<-chan Event, func()) {
	if buffer <= 0 {
		buffer = 32
	}
	ch := make(chan Event, buffer)
	broker.mu.Lock()
	broker.nextID++
	id := broker.nextID
	broker.subscribers[id] = ch
	broker.mu.Unlock()

	cancel := func() {
		broker.mu.Lock()
		if existing, ok := broker.subscribers[id]; ok {
			delete(broker.subscribers, id)
			close(existing)
		}
		broker.mu.Unlock()
	}
	return ch, cancel
}
