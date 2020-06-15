package com.iexec.worker.compute;

import com.iexec.common.utils.FileHelper;
import com.iexec.common.utils.WaitUtils;
import com.iexec.worker.sgx.SgxService;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient.ListContainersParam;
import com.spotify.docker.client.DockerClient.ListNetworksParam;
import com.spotify.docker.client.DockerClient.LogsParam;
import com.spotify.docker.client.exceptions.DockerCertificateException;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.Container;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;
import com.spotify.docker.client.messages.Device;
import com.spotify.docker.client.messages.EndpointConfig;
import com.spotify.docker.client.messages.HostConfig;
import com.spotify.docker.client.messages.Network;
import com.spotify.docker.client.messages.NetworkConfig;
import com.spotify.docker.client.messages.ContainerConfig.NetworkingConfig;
import com.spotify.docker.client.messages.HostConfig.Bind;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;


@Slf4j
@Service
public class DockerService {

    private static final String WORKER_DOCKER_NETWORK = "iexec-worker-net";
    private static final String EXITED = "exited";

    private DefaultDockerClient docker;

    public DockerService() throws DockerCertificateException {
        docker = DefaultDockerClient.fromEnv().build();
        if (getNetworkListByName(WORKER_DOCKER_NETWORK).isEmpty()) {
            createNetwork(WORKER_DOCKER_NETWORK);
        }
    }

    // docker image

    public boolean pullImage(String chainTaskId, String image) {
        log.info("Image pull started [chainTaskId:{}, image:{}]", chainTaskId, image);
        try {
            docker.pull(image);
        } catch (DockerException | InterruptedException e) {
            log.error("Image pull failed [chainTaskId:{}, image:{}]", chainTaskId, image);
            e.printStackTrace();
            return false;
        }

        log.info("Image pull completed [chainTaskId:{}, image:{}]", chainTaskId, image);
        return true;
    }

    public boolean isImagePulled(String image) {
        try {
            return !docker.inspectImage(image).id().isEmpty();
        } catch (DockerException | InterruptedException e) {
            log.error("Failed to check if image was pulled [image:{}]", image);
            e.printStackTrace();
            return false;
        }
    }

    // docker container

    /**
     * maxExecutionTime == 0: the container will be considered a service
     *      and will run without a timeout (such as the LAS service)
     * 
     * maxExecutionTime != 0: we wait for the execution to end or we stop 
     *      the container when it reaches the deadline.
     * 
     * @return
     * - Valid Optional object (with an compute stdout or empty string) when success.
     * - Optional.empty() if failure.
     */

    public Optional<String> run(DockerCompute dockerCompute) {
        String chainTaskId = dockerCompute.getChainTaskId();
        String containerName = dockerCompute.getContainerName();
        long maxExecutionTime = dockerCompute.getMaxExecutionTime();

        log.info("Executing [image:{}, cmd:{}]", dockerCompute.getImageUri(), dockerCompute.getStringArgsCmd());

        Optional<ContainerConfig> oContainerConfig = buildContainerConfig(dockerCompute);
        if (oContainerConfig.isEmpty()) {
            log.error("Cannot execute since container config is null");
            return Optional.empty();
        }

        String containerId = createAndStartContainer(containerName, oContainerConfig.get());
        if (containerId.isEmpty()) {
            return Optional.empty();
        }

        // if the name wasn't specified, get the generated one
        if (containerName == null || containerName.isEmpty()) {
            Optional<Container> oContainer = getContainer(byId(containerId));
            containerName = oContainer.isPresent() ? oContainer.get().names().get(0) : "";
        }

        if (maxExecutionTime == 0) {
            return Optional.of("");
        }

        Date executionTimeoutDate = Date.from(Instant.now().plusMillis(maxExecutionTime));
        waitContainer(containerName, containerId, executionTimeoutDate);
        log.info("End of execution [containerName:{}, containerId:{}]",
                containerName, containerId);

        stopContainer(containerId);
        Optional<String> stdout = getContainerLogs(containerId);
        removeContainer(containerId);

        if (stdout.isEmpty()) {
            log.error("Couldn't get execution logs [chainTaskId:{}]", chainTaskId);
        }
        return stdout;
    }

    public Optional<ContainerConfig> buildContainerConfig(DockerCompute dockerCompute) {
        String imageUri = dockerCompute.getImageUri();
        String[] arrayArgsCmd = dockerCompute.getArrayArgsCmd();
        List<String> env = dockerCompute.getEnv();
        String port = dockerCompute.getContainerPort();
        boolean isSgx = dockerCompute.isSgx();
        ContainerConfig.Builder containerConfigBuilder = ContainerConfig.builder();

        HostConfig hostConfig = createHostConfig(dockerCompute.getBindPaths(), isSgx);
        if (hostConfig == null) {
            return Optional.empty();
        }

        containerConfigBuilder.hostConfig(hostConfig);
        
        if (imageUri == null || imageUri.isEmpty()) {
            return Optional.empty();
        }

        containerConfigBuilder.image(imageUri);

        if (arrayArgsCmd != null && arrayArgsCmd.length != 0) {
            containerConfigBuilder.cmd(arrayArgsCmd);
        }

        if (port != null && !port.isEmpty()) {
            containerConfigBuilder.exposedPorts(port);
        }

        if (env != null && !env.isEmpty()) {
            containerConfigBuilder.env(env);
        }

        // attach container to "iexec-worker-net" network
        EndpointConfig endpointConfig = EndpointConfig.builder().build();
        Map<String, EndpointConfig> endpointConfigMap = Map.of(WORKER_DOCKER_NETWORK, endpointConfig);
        NetworkingConfig networkingConfig = NetworkingConfig.create(endpointConfigMap);
        containerConfigBuilder.networkingConfig(networkingConfig);
        return Optional.of(containerConfigBuilder.build());
    }

    private HostConfig createHostConfig(Map<String, String> bindPaths, boolean isSgx) {
        HostConfig.Builder hostConfigBuilder = HostConfig.builder();

        if (isSgx) {
            Device sgxDevice = Device.builder()
                    .pathOnHost(SgxService.SGX_DEVICE_PATH)
                    .pathInContainer(SgxService.SGX_DEVICE_PATH)
                    .cgroupPermissions(SgxService.SGX_CGROUP_PERMISSIONS)
                    .build();

            hostConfigBuilder.devices(sgxDevice);
        }

        if (bindPaths == null) {
            return hostConfigBuilder.build();
        }

        for (String source : bindPaths.keySet()) {
            Bind bind = createBind(source, bindPaths.get(source));
            if (bind == null) {
                // we should stop since an error occurred
                log.error("Cannot continue, problem creating volume binds");
                return null;            
            }

            hostConfigBuilder.appendBinds(bind);
        }

        return hostConfigBuilder.build();
    }

    private HostConfig.Bind createBind(String source, String dest) {
        if (source == null || source.isEmpty() || dest == null || dest.isEmpty()) {
            return null;
        }

        boolean isSourceMountPointSet = FileHelper.createFolder(source);
        if (!isSourceMountPointSet) {
            log.error("Mount point does not exist on host [mountPoint:{}]", source);
            return null;
        }

        return HostConfig.Bind.from(source)
                .to(dest)
                .readOnly(false)
                .build();
    }

    public String createAndStartContainer(String containerName, ContainerConfig containerConfig) {
        if (containerConfig == null) {
            log.error("Cannot create container since config is null [containerName:{}]", containerName);
            return "";
        }

        if (containerName != null && !containerName.isEmpty() && getContainer(byName(containerName)).isPresent()) {
            log.info("Found duplicate container with the same name, will remove it [containerName:{}]", containerName);
            stopAndRemoveContainer(containerName);
        }

        // docker container create
        String containerId = createContainer(containerName, containerConfig);
        if (containerId.isEmpty()) {
            return "";
        }

        // docker container start
        boolean isContainerStarted = startContainer(containerId);
        if (!isContainerStarted) {
            removeContainer(containerId);
            return "";
        }

        return containerId;
    }

    public String createContainer(String containerName, ContainerConfig containerConfig) {
        log.debug("Creating container [containerName:{}]", containerName);

        if (containerConfig == null) {
            return "";
        }

        ContainerCreation containerCreation;
        try {
            if (containerName == null || containerName.isEmpty()) {
                containerCreation = docker.createContainer(containerConfig);
            } else {
                containerCreation = docker.createContainer(containerConfig, containerName);
            }
        } catch (DockerException | InterruptedException e) {
            log.error("Failed to create container [containerName:{}, image:{}, cmd:{}]",
                    containerName, containerConfig.image(), containerConfig.cmd());
            e.printStackTrace();
            return "";
        }

        if (containerCreation == null) {
            log.error("Error creating container [containerName:{}]", containerName);
            return "";
        }

        String containerId = containerCreation.id();
        log.info("Created container [containerName:{}, containerId:{}]",
                containerName, containerId);
        return containerId;
    }

    public boolean startContainer(String containerId) {
        log.debug("Starting container [containerId:{}]", containerId);

        try {
            docker.startContainer(containerId);
        } catch (DockerException | InterruptedException e) {
            log.error("Failed to start container [containerId:{}]", containerId);
            e.printStackTrace();
            return false;
        }

        log.debug("Started container [containerId:{}]", containerId);
        return true;
    }

    public void waitContainer(String containerName, String containerId, Date executionTimeoutDate) {
        boolean isComputed = false;
        boolean isTimeout = false;

        if (containerId == null || containerId.isEmpty()) {
            return;
        }

        int seconds = 0;
        while (!isComputed && !isTimeout) {
            if (seconds % 60 == 0){ //don't display logs too often
                log.info("Still running [containerName:{}, containerId:{}, status:{}, isComputed:{}, isTimeout:{}]",
                        containerName, containerId, getContainerStatus(containerId), isComputed, isTimeout);
            }

            WaitUtils.sleep(1);
            isComputed = isContainerExited(containerId);
            isTimeout = isAfterTimeout(executionTimeoutDate);
            seconds++;
        }

        if (isTimeout) {
            log.warn("Container reached timeout, stopping [containerName:{}, containerId:{}]",
            containerName, containerId);
        }
    }

    public boolean stopContainer(String containerId) {
        log.debug("Stopping container [containerId:{}]", containerId);

        try {
            docker.stopContainer(containerId, 0);
        } catch (DockerException | InterruptedException e) {
            log.error("Failed to stop container [containerId:{}]", containerId);
            e.printStackTrace();
            return false;
        }

        log.debug("Stopped container [containerId:{}]", containerId);
        return true;
    }

    public Optional<String> getContainerLogs(String containerId) {
        log.debug("Getting container logs [containerId:{}]", containerId);

        try {
            String stdout = docker.logs(containerId, LogsParam.stdout(), LogsParam.stderr()).readFully();
            log.debug("Got container logs [containerId:{}]", containerId);
            return Optional.of(stdout);    
        } catch (DockerException | InterruptedException e) {
            log.error("Failed to get container logs [containerId:{}]", containerId);
            e.printStackTrace();
            return Optional.empty();
        }
    }

    public boolean removeContainer(String containerId) {
        log.debug("Removing container [containerId:{}]", containerId);
        try {
            docker.removeContainer(containerId);
        } catch (DockerException | InterruptedException e) {
            log.error("Failed to remove container [containerId:{}]", containerId);
            e.printStackTrace();
            return false;
        }

        log.info("Removed container [containerId:{}]", containerId);
        return true;
    }

    public void stopAndRemoveContainer(String containerName) {
        Optional<Container> oContainer = getContainer(byName(containerName));
        if (oContainer.isPresent()) {
            String containerId = oContainer.get().id();
            stopContainer(containerId);
            removeContainer(containerId);
        }
    }

    private Optional<Container> getContainer(Predicate<Container> predicate) {
        List<Container> containerList = new ArrayList<>();
        try {
            containerList = docker.listContainers(ListContainersParam.allContainers())
                    .stream()
                    .filter(predicate)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to get container");
            e.printStackTrace();
            return Optional.empty();
        }

        return !containerList.isEmpty() ? Optional.of(containerList.get(0)) : Optional.empty();
    }

    private Predicate<Container> byName(String containerName) {
        if (containerName == null || containerName.isEmpty()) {
            return (container) -> false;
        }

        // We test the name with "/" and without it because
        // the behavior of the docker library is not clear
        return (container) -> container.names().contains("/" + containerName) ||
                              container.names().contains(containerName);
    }

    private Predicate<Container> byId(String containerId) {
        if (containerId == null || containerId.isEmpty()) {
            return (container) -> false;
        }

        return (container) -> container.id().equals(containerId);
    }

    private boolean isContainerExited(String containerId) {
        return getContainerStatus(containerId).equals(EXITED);
    }

    private String getContainerStatus(String containerId) {
        try {
            return docker.inspectContainer(containerId).state().status();
        } catch (DockerException | InterruptedException e) {
            log.error("Failed to get container status [containerId:{}]", containerId);
            e.printStackTrace();
            return "";
        }
    }

    private boolean isAfterTimeout(Date executionTimeoutDate) {
        return new Date().after(executionTimeoutDate);
    }

    // docker network

    private List<Network> getNetworkListByName(String networkName) {
        log.debug("Getting network list by name [networkName:{}]", networkName);

        try {
            return docker.listNetworks(ListNetworksParam.byNetworkName(networkName));
        } catch (Exception e) {
            log.error("Failed to get network list by name [networkName:{}]", networkName);
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    private String createNetwork(String networkName) {
        log.debug("Creating network [networkName:{}]", networkName);
        NetworkConfig networkConfig = NetworkConfig.builder()
                .driver("bridge")
                .checkDuplicate(true)
                .name(networkName)
                .build();

        String networkId;
        try {
            networkId = docker.createNetwork(networkConfig).id();
        } catch (Exception e) {
            log.error("Failed to create docker network [name:{}]", networkName);
            e.printStackTrace();
            return "";
        }

        log.debug("Created network [networkName:{}, networkId]", networkName, networkId);
        return networkId;
    }

    private void removeNetwork(String networkId) {
        log.debug("Removing network [networkId:{}]", networkId);
        try {
            docker.removeNetwork(networkId);
            log.debug("Removed network [networkId:{}]", networkId);
        } catch (Exception e) {
            log.error("Failed to remove docker network [networkId:{}]", networkId);
            e.printStackTrace();
        }
    }

    // TODO: clean all containers connecting to the network before removing it
    @PreDestroy
    void onPreDestroy() {
        for (Network network : getNetworkListByName(WORKER_DOCKER_NETWORK)) {
            removeNetwork(network.id());
        }
        docker.close();
    }
}