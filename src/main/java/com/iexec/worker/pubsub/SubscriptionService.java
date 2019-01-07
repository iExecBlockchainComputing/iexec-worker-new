package com.iexec.worker.pubsub;

import com.iexec.common.result.TaskNotification;
import com.iexec.common.result.TaskNotificationType;
import com.iexec.worker.chain.RevealService;
import com.iexec.worker.config.CoreConfigurationService;
import com.iexec.worker.config.WorkerConfigurationService;
import com.iexec.worker.feign.CustomFeignClient;
import com.iexec.worker.feign.ResultRepoClient;
import com.iexec.worker.result.ResultService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import javax.annotation.PostConstruct;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.iexec.common.replicate.ReplicateStatus.*;

@Slf4j
@Service
public class SubscriptionService extends StompSessionHandlerAdapter {

    private final String coreHost;
    private final int corePort;
    private final String workerWalletAddress;
    // external services
    private ResultRepoClient resultRepoClient;
    private ResultService resultService;
    private RevealService revealService;
    private CustomFeignClient feignClient;
    // internal components
    private StompSession session;
    private Map<String, StompSession.Subscription> chainTaskIdToSubscription;

    public SubscriptionService(CoreConfigurationService coreConfigurationService,
                               WorkerConfigurationService workerConfigurationService,
                               ResultRepoClient resultRepoClient,
                               ResultService resultService,
                               RevealService revealService,
                               CustomFeignClient feignClient) {
        this.resultRepoClient = resultRepoClient;
        this.resultService = resultService;
        this.revealService = revealService;
        this.feignClient = feignClient;

        this.coreHost = coreConfigurationService.getHost();
        this.corePort = coreConfigurationService.getPort();
        this.workerWalletAddress = workerConfigurationService.getWorkerWalletAddress();

        chainTaskIdToSubscription = new ConcurrentHashMap<>();
    }

    @PostConstruct
    private void run() {
        WebSocketClient webSocketClient = new StandardWebSocketClient();
        WebSocketStompClient stompClient = new WebSocketStompClient(webSocketClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        stompClient.setTaskScheduler(new ConcurrentTaskScheduler());

        String url = "ws://" + coreHost + ":" + corePort + "/connect";
        stompClient.connect(url, this);
    }

    @Override
    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
        log.info("SubscriptionService set up [session: {}]", session.getSessionId());
        this.session = session;
    }

    @Override
    public void handleException(StompSession session, @Nullable StompCommand command,
                                StompHeaders headers, byte[] payload, Throwable exception) {
        log.info("Received handleException [session: {}]", session.getSessionId());
    }


    @Override
    public void handleTransportError(StompSession session, Throwable exception) {
        log.info("Received handleTransportError [session: {}]", session.getSessionId());

    }


    public void subscribeToTaskNotifications(String chainTaskId) {
        if (chainTaskIdToSubscription.containsKey(chainTaskId)) {
            log.info("Already subscribed to TaskNotification [chainTaskId:{}]", chainTaskId);
            return;
        }
        StompSession.Subscription subscription = session.subscribe(getTaskTopicName(chainTaskId), new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return TaskNotification.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                TaskNotification taskNotification = (TaskNotification) payload;
                handleTaskNotification(taskNotification);
            }
        });
        chainTaskIdToSubscription.put(chainTaskId, subscription);
        log.info("Subscribed to topic [chainTaskId:{}, topic:{}]", chainTaskId, getTaskTopicName(chainTaskId));
    }

    private void handleTaskNotification(TaskNotification notif) {
        if (notif.getWorkersAddress().contains(workerWalletAddress)
                || notif.getWorkersAddress().isEmpty()) {
            log.info("Received notification [notification:{}]", notif);

            TaskNotificationType type = notif.getTaskNotificationType();
            String chainTaskId = notif.getChainTaskId();

            switch (type) {
                case PLEASE_ABORT_CONTRIBUTION_TIMEOUT:
                    abortContributionTimeout(chainTaskId);
                    break;

                case PLEASE_ABORT_CONSENSUS_REACHED:
                    abortConsensusReached(chainTaskId);
                    break;

                case PLEASE_REVEAL:
                reveal(chainTaskId);
                    break;

                case PLEASE_UPLOAD:
                    uploadResult(chainTaskId);
                    break;

                case COMPLETED:
                    completeTask(chainTaskId);
                    break;

                default:
                    break;
            }
        }
    }

    private void reveal(String chainTaskId) {
        log.info("Trying to reveal [chainTaskId:{}]", chainTaskId);
        if (!revealService.canReveal(chainTaskId)) {
            log.warn("The worker will not be able to reveal [chainTaskId:{}]", chainTaskId);
            feignClient.updateReplicateStatus(chainTaskId, CANT_REVEAL);
        }

        if (!revealService.hasEnoughGas()) {
            feignClient.updateReplicateStatus(chainTaskId, OUT_OF_GAS);
            System.exit(0);
        }

        feignClient.updateReplicateStatus(chainTaskId, REVEALING);
        if (revealService.reveal(chainTaskId)) {
            feignClient.updateReplicateStatus(chainTaskId, REVEALED);
        } else {
            feignClient.updateReplicateStatus(chainTaskId, REVEAL_FAILED);
        }
    }

    private void abortConsensusReached(String chainTaskId) {
        cleanReplicate(chainTaskId);
        feignClient.updateReplicateStatus(chainTaskId, ABORT_CONSENSUS_REACHED);
    }

    private void abortContributionTimeout(String chainTaskId) {
        cleanReplicate(chainTaskId);
        feignClient.updateReplicateStatus(chainTaskId, ABORT_CONTRIBUTION_TIMEOUT);
    }

    private void uploadResult(String chainTaskId) {
        feignClient.updateReplicateStatus(chainTaskId, RESULT_UPLOADING);
        resultRepoClient.addResult(resultService.getResultModelWithZip(chainTaskId));
        feignClient.updateReplicateStatus(chainTaskId, RESULT_UPLOADED);
    }

    private void completeTask(String chainTaskId) {
        cleanReplicate(chainTaskId);
        feignClient.updateReplicateStatus(chainTaskId, COMPLETED);
    }

    private void unsubscribeFromTaskNotifications(String chainTaskId) {
        if (!chainTaskIdToSubscription.containsKey(chainTaskId)) {
            log.info("Already unsubscribed from TaskNotification [chainTaskId:{}]", chainTaskId);
            return;
        }
        chainTaskIdToSubscription.get(chainTaskId).unsubscribe();
        chainTaskIdToSubscription.remove(chainTaskId);
        log.info("Unsubscribed from taskNotification [chainTaskId:{}]", chainTaskId);
    }

    private void cleanReplicate(String chainTaskId) {
        // unsubscribe from the topic and remove the associated result from the machine
        unsubscribeFromTaskNotifications(chainTaskId);
        resultService.removeResult(chainTaskId);
    }

    private String getTaskTopicName(String chainTaskId) {
        return "/topic/task/" + chainTaskId;
    }
}