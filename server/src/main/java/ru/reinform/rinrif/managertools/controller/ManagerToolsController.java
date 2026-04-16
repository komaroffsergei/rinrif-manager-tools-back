package ru.reinform.rinrif.managertools.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.reinform.rinrif.managertools.core.ManagerToolsService;
import ru.reinform.rinrif.managertools.model.ApiModels.AddRepositoryRequest;
import ru.reinform.rinrif.managertools.model.ApiModels.AddRepositoryResponse;
import ru.reinform.rinrif.managertools.model.ApiModels.DeleteResponse;
import ru.reinform.rinrif.managertools.model.ApiModels.QueuedJobResponse;
import ru.reinform.rinrif.managertools.model.ApiModels.RepositoryRecord;
import ru.reinform.rinrif.managertools.model.ApiModels.RepositorySummary;
import ru.reinform.rinrif.managertools.model.ApiModels.SearchJobRecord;
import ru.reinform.rinrif.managertools.model.ApiModels.SearchRequestPayload;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ManagerToolsController {
    private final ManagerToolsService managerToolsService;

    public ManagerToolsController(ManagerToolsService managerToolsService) {
        this.managerToolsService = managerToolsService;
    }

    @GetMapping("/repositories")
    public List<RepositoryRecord> listRepositories() {
        return managerToolsService.listRepositories();
    }

    @GetMapping("/repositories/{repoId}")
    public RepositoryRecord getRepository(@PathVariable String repoId) {
        return managerToolsService.getRepository(repoId);
    }

    @PostMapping("/repositories")
    public AddRepositoryResponse addRepository(@RequestBody AddRepositoryRequest request) {
        RepositoryRecord repository = managerToolsService.addRepository(request == null ? "" : request.url);
        return new AddRepositoryResponse(new RepositorySummary(repository.id, repository.name, repository.status));
    }

    @PostMapping("/repositories/{repoId}/update")
    public QueuedJobResponse updateRepository(@PathVariable String repoId) {
        return managerToolsService.startUpdate(repoId);
    }

    @DeleteMapping("/repositories/{repoId}")
    public DeleteResponse deleteRepository(@PathVariable String repoId) {
        managerToolsService.deleteRepository(repoId);
        return new DeleteResponse();
    }

    @PostMapping("/search")
    public QueuedJobResponse startSearch(@RequestBody SearchRequestPayload request) {
        return managerToolsService.startSearch(request);
    }

    @GetMapping("/jobs/{jobId}")
    public SearchJobRecord getJob(@PathVariable String jobId) {
        return managerToolsService.getJob(jobId);
    }
}
