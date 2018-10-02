package com.iexec.worker;


import com.iexec.common.replicate.ReplicateModel;
import com.iexec.common.replicate.ReplicateStatus;
import com.iexec.worker.docker.ContainerResult;
import com.iexec.worker.docker.DockerService;
import com.iexec.worker.feign.CoreTaskClient;
import com.iexec.worker.utils.WorkerConfigurationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Controller {


    @Value("${worker.name}")
    private String workerName;
    private CoreTaskClient coreTaskClient;

    private DockerService dockerService;
    private WorkerConfigurationService workerConfigurationService;

    @Autowired
    public Controller(CoreTaskClient coreTaskClient,
                      DockerService dockerService,
                      WorkerConfigurationService workerConfigurationService) {
        this.coreTaskClient = coreTaskClient;
        this.dockerService = dockerService;
        this.workerConfigurationService = workerConfigurationService;
    }

    @GetMapping("/getTask")
    public String getTask() {
        String workerName = workerConfigurationService.getWorkerName();
        ReplicateModel replicate = coreTaskClient.getReplicate(workerName).getBody();
        if (replicate.getTaskId() == null) {
            return "NO TASK AVAILABLE";
        }

        coreTaskClient.updateReplicateStatus(replicate.getTaskId(), ReplicateStatus.RUNNING, workerName);

        // simulate some work on the task
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        coreTaskClient.updateReplicateStatus(replicate.getTaskId(), ReplicateStatus.COMPLETED, workerName);
        return ReplicateStatus.COMPLETED.toString();
    }

    //http://localhost:18091/docker/run?image=iexechub/vanityeth:latest&cmd=a
    @GetMapping("/docker/run")
    public ContainerResult dockerRun(@RequestParam(name = "image", required = false, defaultValue = "iexechub/vanityeth:latest") String image,
                                     @RequestParam(name = "cmd", required = false, defaultValue = "") String cmd) {
        return dockerService.dockerRun(image, cmd);
    }

}