package com.iexec.worker;

import com.iexec.worker.feign.CoreWorkerClient;
import com.iexec.worker.config.WorkerConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PingService {

    private final CoreWorkerClient coreWorkerClient;
    private WorkerConfigurationService workerConfService;

    public PingService(CoreWorkerClient coreWorkerClient,
                       WorkerConfigurationService workerConfService) {
        this.coreWorkerClient = coreWorkerClient;
        this.workerConfService = workerConfService;
    }

    @Scheduled(fixedRate = 10000)
    public void pingScheduler() {
        log.debug("Send ping to scheduler");
        coreWorkerClient.ping(workerConfService.getWorkerWalletAddress());
    }
}
