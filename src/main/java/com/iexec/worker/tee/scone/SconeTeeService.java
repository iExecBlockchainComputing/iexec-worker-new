package com.iexec.worker.tee.scone;

import java.util.List;

import javax.annotation.PreDestroy;

import com.iexec.worker.compute.CustomDockerClient;
import com.iexec.worker.compute.DockerExecutionConfig;
import com.iexec.worker.compute.DockerExecutionResult;
import com.iexec.worker.sgx.SgxService;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;


@Slf4j
@Service
public class SconeTeeService {

    private SconeLasConfiguration sconeLasConfig;
    private CustomDockerClient customDockerClient;
    private boolean isLasStarted;

    public SconeTeeService(SconeLasConfiguration sconeLasConfig,
                           CustomDockerClient customDockerClient,
                           SgxService sgxService) {
        this.sconeLasConfig = sconeLasConfig;
        this.customDockerClient = customDockerClient;
        isLasStarted = sgxService.isSgxEnabled() ? startLasService() : false;
    }

    public boolean isTeeEnabled() {
        return isLasStarted;
    }

    private boolean startLasService() {
        String chainTaskId = "iexec-las";

        DockerExecutionConfig dockerExecutionConfig = DockerExecutionConfig.builder()
                .chainTaskId(chainTaskId)
                .containerName(sconeLasConfig.getContainerName())
                .imageUri(sconeLasConfig.getImageUri())
                .containerPort(sconeLasConfig.getPort())
                .isSgx(true)
                .maxExecutionTime(0)
                .build();

        if (!customDockerClient.pullImage(chainTaskId, sconeLasConfig.getImageUri())) {
            return false;
        }

        DockerExecutionResult dockerExecutionResult = customDockerClient.execute(dockerExecutionConfig);

        if (!dockerExecutionResult.isSuccess()) {
            log.error("Couldn't start LAS service, will continue without TEE support");
            return false;
        }

        return true;
    }

    public List<String> buildSconeDockerEnv(String sconeConfigId, String sconeCasUrl, String sconeHeap) {
        SconeConfig sconeConfig = SconeConfig.builder()
                .sconeLasAddress(sconeLasConfig.getUrl())
                .sconeCasAddress(sconeCasUrl)
                .sconeConfigId(sconeConfigId)
                .sconeHeap(sconeHeap)
                .build();

        return sconeConfig.toDockerEnv();
    }

    @PreDestroy
    void stopLasService() {
        if (isLasStarted) {
            customDockerClient.stopAndRemoveContainer(sconeLasConfig.getContainerName());
        }
    }
}