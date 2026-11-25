package web

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/nihildigit/piko/download-server/internal/store"
	"github.com/nihildigit/piko/download-server/internal/updater"
)

type updateStub struct {
	info      updater.Info
	result    updater.Result
	applied   bool
	restarted bool
}

func (stub *updateStub) Check(context.Context, bool) (updater.Info, error) {
	return stub.info, nil
}

func (stub *updateStub) Apply(context.Context) (updater.Result, error) {
	stub.applied = true
	return stub.result, nil
}

func (stub *updateStub) ScheduleRestart(time.Duration) {
	stub.restarted = true
}

func (stub *updateStub) CurrentVersion() string {
	return stub.info.CurrentVersion
}

func TestUpdateHandlers(t *testing.T) {
	stub := &updateStub{
		info: updater.Info{
			CurrentVersion: "1.0.0",
			LatestVersion:  "2.0.0",
			Available:      true,
			CanApply:       true,
		},
		result: updater.Result{Version: "2.0.0", Restarting: true},
	}
	srv := &Server{updater: stub}

	infoRec := httptest.NewRecorder()
	infoReq := httptest.NewRequest(http.MethodGet, "/api/system/update", nil)
	srv.handleUpdateInfo(infoRec, infoReq, store.AdminSession{})
	if infoRec.Code != http.StatusOK {
		t.Fatalf("unexpected info status: %d", infoRec.Code)
	}
	var info updater.Info
	if err := json.NewDecoder(infoRec.Body).Decode(&info); err != nil {
		t.Fatal(err)
	}
	if !info.Available || info.LatestVersion != "2.0.0" {
		t.Fatalf("unexpected info payload: %+v", info)
	}

	applyRec := httptest.NewRecorder()
	applyReq := httptest.NewRequest(http.MethodPost, "/api/system/update/apply", nil)
	srv.handleApplyUpdate(applyRec, applyReq, store.AdminSession{})
	if applyRec.Code != http.StatusOK {
		t.Fatalf("unexpected apply status: %d body=%s", applyRec.Code, applyRec.Body.String())
	}
	if !stub.applied || !stub.restarted {
		t.Fatalf("expected apply and restart, got applied=%v restarted=%v", stub.applied, stub.restarted)
	}
}

func TestUpdateHandlerWithoutService(t *testing.T) {
	srv := &Server{}
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/system/update", nil)
	srv.handleUpdateInfo(rec, req, store.AdminSession{})
	if rec.Code != http.StatusOK {
		t.Fatalf("unexpected status: %d", rec.Code)
	}
	var info updater.Info
	if err := json.NewDecoder(rec.Body).Decode(&info); err != nil {
		t.Fatal(err)
	}
	if info.CanApply || info.Reason == "" {
		t.Fatalf("unexpected disabled update payload: %+v", info)
	}
}
