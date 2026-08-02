package libcore

import (
	"context"
	"errors"
	"testing"
	"time"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/option"
)

func TestAndroidLocalTransportExchangeAsyncCancellation(t *testing.T) {
	originalRawQueryFunc := rawQueryFunc
	defer func() { rawQueryFunc = originalRawQueryFunc }()

	rawQueryFunc = func(ctx context.Context, _ int64, _ []byte) ([]byte, error) {
		<-ctx.Done()
		return nil, ctx.Err()
	}

	ctx, cancel := context.WithCancel(context.Background())
	message := new(mDNS.Msg)
	message.SetQuestion("example.com.", mDNS.TypeA)
	result := make(chan error, 1)

	newAndroidLocalTransport("local", option.LocalDNSServerOptions{}).ExchangeAsync(ctx, message, func(_ *mDNS.Msg, err error) {
		result <- err
	})
	cancel()

	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("expected context cancellation, got %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("asynchronous exchange did not stop after cancellation")
	}
}
