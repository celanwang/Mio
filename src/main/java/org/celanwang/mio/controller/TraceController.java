package org.celanwang.mio.controller;

import org.celanwang.mio.trace.TraceEvent;
import org.celanwang.mio.trace.TraceStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/trace")
public class TraceController {

    private final TraceStore traceStore;

    public TraceController(TraceStore traceStore) {
        this.traceStore = traceStore;
    }

    @GetMapping("/sessions")
    public List<TraceStore.SessionSummary> sessions() {
        return traceStore.sessions();
    }

    @GetMapping("/{sessionId}")
    public List<TraceEvent> events(@PathVariable String sessionId) {
        return traceStore.events(sessionId);
    }
}
