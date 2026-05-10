package ru.reinform.rinrif.managertools.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.reinform.rinrif.managertools.atr2spec.Atr2SpecModels.Atr2SpecArtifact;
import ru.reinform.rinrif.managertools.atr2spec.Atr2SpecModels.Atr2SpecJobRecord;
import ru.reinform.rinrif.managertools.atr2spec.Atr2SpecModels.Atr2SpecQueuedResponse;
import ru.reinform.rinrif.managertools.atr2spec.Atr2SpecModels.Atr2SpecRunRequest;
import ru.reinform.rinrif.managertools.atr2spec.Atr2SpecService;

@RestController
@RequestMapping("/api/atr2spec")
public class Atr2SpecController {
    private final Atr2SpecService atr2SpecService;

    public Atr2SpecController(Atr2SpecService atr2SpecService) {
        this.atr2SpecService = atr2SpecService;
    }

    @PostMapping("/runs")
    public Atr2SpecQueuedResponse startRun(@RequestBody Atr2SpecRunRequest request) {
        return atr2SpecService.startRun(request);
    }

    @GetMapping("/runs/{jobId}")
    public Atr2SpecJobRecord getRun(@PathVariable String jobId) {
        return atr2SpecService.getRun(jobId);
    }

    @GetMapping("/runs/{jobId}/draft")
    public ResponseEntity<String> getDraft(@PathVariable String jobId) {
        Atr2SpecArtifact artifact = atr2SpecService.getDraft(jobId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(artifact.mediaType))
                .body(artifact.content);
    }

    @GetMapping("/runs/{jobId}/artifact/{name}")
    public ResponseEntity<String> getArtifact(@PathVariable String jobId, @PathVariable String name) {
        Atr2SpecArtifact artifact = atr2SpecService.getArtifact(jobId, name);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(artifact.mediaType))
                .body(artifact.content);
    }
}
