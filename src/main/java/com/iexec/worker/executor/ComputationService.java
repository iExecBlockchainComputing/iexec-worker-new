package com.iexec.worker.executor;

import com.iexec.common.chain.ContributionAuthorization;
import com.iexec.common.replicate.AvailableReplicateModel;
import com.iexec.common.replicate.ReplicateStatus;
import com.iexec.worker.dataset.DatasetService;
import com.iexec.worker.docker.DockerComputationService;
import com.iexec.worker.sms.SmsService;
import com.iexec.worker.tee.scone.SconeTeeService;
import com.iexec.worker.utils.FileHelper;
import org.apache.commons.lang3.tuple.Pair;
import com.spotify.docker.client.messages.ContainerConfig;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import static com.iexec.common.replicate.ReplicateStatus.*;


@Slf4j
@Service
public class ComputationService {

    private SmsService smsService;
    private DatasetService datasetService;
    private DockerComputationService dockerComputationService;
    private SconeTeeService sconeTeeService;

    public ComputationService(SmsService smsService,
                              DatasetService datasetService,
                              DockerComputationService dockerComputationService,
                              SconeTeeService sconeTeeService) {

        this.smsService = smsService;
        this.datasetService = datasetService;
        this.dockerComputationService = dockerComputationService;
        this.sconeTeeService = sconeTeeService;
    }


    public Pair<ReplicateStatus, String> runTeeComputation(AvailableReplicateModel replicateModel) {
        ContributionAuthorization contributionAuth = replicateModel.getContributionAuthorization();
        String chainTaskId = contributionAuth.getChainTaskId();
        String stdout;

        String secureSessionId = sconeTeeService.createSconeSecureSession(contributionAuth);

        if (secureSessionId.isEmpty()) {
            stdout = "Could not generate scone secure session for tee computation";
            log.error(stdout + " [chainTaskId:{}]", chainTaskId);
            return Pair.of(COMPUTE_FAILED, stdout);
        }

        ContainerConfig teeContainerConfig = sconeTeeService.buildSconeContainerConfig(secureSessionId, replicateModel);

        stdout = dockerComputationService.dockerRunAndGetLogs(teeContainerConfig, chainTaskId, replicateModel.getMaxExecutionTime());

        if (stdout.isEmpty()) {
            stdout = "Failed to start computation";
            log.error(stdout + " [chainTaskId:{}]", chainTaskId);
            return Pair.of(COMPUTE_FAILED, stdout);
        }

        return Pair.of(COMPUTED, stdout);
    }

    public Pair<ReplicateStatus, String> runNonTeeComputation(AvailableReplicateModel replicateModel) {
        ContributionAuthorization contributionAuth = replicateModel.getContributionAuthorization();
        String chainTaskId = contributionAuth.getChainTaskId();
        String stdout = "";

        // fetch task secrets from SMS
        boolean isFetched = smsService.fetchTaskSecrets(contributionAuth);
        if (!isFetched) {
            log.warn("No secrets fetched for this task, will continue [chainTaskId:{}]:", chainTaskId);
        }

        // decrypt data
        boolean isDatasetDecryptionNeeded = datasetService.isDatasetDecryptionNeeded(chainTaskId);
        boolean isDatasetDecrypted = false;

        if (isDatasetDecryptionNeeded) {
            isDatasetDecrypted = datasetService.decryptDataset(chainTaskId, replicateModel.getDatasetUri());
        }

        if (isDatasetDecryptionNeeded && !isDatasetDecrypted) {
            stdout = "Failed to decrypt dataset, URI:" + replicateModel.getDatasetUri();
            log.error(stdout + " [chainTaskId:{}]", chainTaskId);
            return Pair.of(COMPUTE_FAILED, stdout);
        }

        // compute
        String datasetFilename = FileHelper.getFilenameFromUri(replicateModel.getDatasetUri());
        stdout = dockerComputationService.dockerRunAndGetLogs(replicateModel, datasetFilename);

        if (stdout.isEmpty()) {
            stdout = "Failed to start computation";
            log.error(stdout + " [chainTaskId:{}]", chainTaskId);
            return Pair.of(COMPUTE_FAILED, stdout);
        }

        return Pair.of(COMPUTED, stdout);        
    }
}