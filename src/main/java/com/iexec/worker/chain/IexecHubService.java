package com.iexec.worker.chain;


import com.iexec.common.chain.*;
import com.iexec.common.contract.generated.IexecHubABILegacy;
import com.iexec.worker.config.PublicConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Sign;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.RemoteCall;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import static com.iexec.common.chain.ChainContributionStatus.CONTRIBUTED;
import static com.iexec.common.chain.ChainContributionStatus.REVEALED;
import static com.iexec.common.utils.BytesUtils.stringToBytes;


@Slf4j
@Service
public class IexecHubService extends IexecHubAbstractService {

    private final CredentialsService credentialsService;
    private final ThreadPoolExecutor executor;
    private final Web3j web3j;
    private Web3jService web3jService;

    @Autowired
    public IexecHubService(CredentialsService credentialsService,
                           Web3jService web3jService,
                           PublicConfigurationService publicConfigurationService) {
        super(credentialsService.getCredentials(), web3jService, publicConfigurationService.getIexecHubAddress());
        this.credentialsService = credentialsService;
        this.web3j = web3jService.getWeb3j();
        this.web3jService = web3jService;
        this.executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
    }

    IexecHubABILegacy.TaskContributeEventResponse contribute(ContributionAuthorization contribAuth,
                                                             String resultHash,
                                                             String resultSeal,
                                                             Sign.SignatureData enclaveSignatureData) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                log.info("Requested  contribute [chainTaskId:{}, waitingTxCount:{}]",
                        contribAuth.getChainTaskId(), getWaitingTransactionCount());
                return sendContributeTransaction(contribAuth, resultHash, resultSeal, enclaveSignatureData);
            }, executor).get();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
        return null;
    }

    private IexecHubABILegacy.TaskContributeEventResponse sendContributeTransaction(ContributionAuthorization contribAuth,
                                                                                    String resultHash,
                                                                                    String resultSeal,
                                                                                    Sign.SignatureData enclaveSignatureData) {
        TransactionReceipt contributeReceipt;
        String chainTaskId = contribAuth.getChainTaskId();
        RemoteCall<TransactionReceipt> contributeCall = getHubContract(web3jService.getWritingContractGasProvider()).contributeABILegacy(
                stringToBytes(chainTaskId),
                stringToBytes(resultHash),
                stringToBytes(resultSeal),
                contribAuth.getEnclave(),
                BigInteger.valueOf(enclaveSignatureData.getV()),
                enclaveSignatureData.getR(),
                enclaveSignatureData.getS(),
                BigInteger.valueOf(contribAuth.getSignV()),
                contribAuth.getSignR(),
                contribAuth.getSignS());
        log.info("Sent contribute [chainTaskId:{}, resultHash:{}]", chainTaskId, resultHash);

        try {
            contributeReceipt = contributeCall.send();
        } catch (Exception e) {
            log.error("Failed to contribute [chainTaskId:{}, exception:{}]", chainTaskId, e.getMessage());
            e.printStackTrace();
            return null;
        }

        List<IexecHubABILegacy.TaskContributeEventResponse> contributeEvents = getHubContract().getTaskContributeEvents(contributeReceipt);

        IexecHubABILegacy.TaskContributeEventResponse contributeEvent = null;
        if (contributeEvents != null && !contributeEvents.isEmpty()) {
            contributeEvent = contributeEvents.get(0);
        }

        if (contributeEvent != null && contributeEvent.log != null &&
                (!contributeEvent.log.getType().equals(PENDING_RECEIPT_STATUS)
                        || isContributionStatusValidOnChainAfterPendingReceipt(chainTaskId, CONTRIBUTED))) {
            log.info("Contributed [chainTaskId:{}, resultHash:{}, gasUsed:{}]",
                    chainTaskId, resultHash, contributeReceipt.getGasUsed());
            return contributeEvent;
        }

        log.error("Failed to contribute [chainTaskId:{}]", chainTaskId);
        return null;
    }

    IexecHubABILegacy.TaskRevealEventResponse reveal(String chainTaskId, String resultDigest) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                log.info("Requested  reveal [chainTaskId:{}, waitingTxCount:{}]", chainTaskId, getWaitingTransactionCount());
                return sendRevealTransaction(chainTaskId, resultDigest);
            }, executor).get();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
        return null;
    }

    private IexecHubABILegacy.TaskRevealEventResponse sendRevealTransaction(String chainTaskId, String resultDigest) {
        TransactionReceipt revealReceipt;
        RemoteCall<TransactionReceipt> revealCall = getHubContract(web3jService.getWritingContractGasProvider()).reveal(
                stringToBytes(chainTaskId),
                stringToBytes(resultDigest));

        log.info("Sent reveal [chainTaskId:{}, resultDigest:{}]", chainTaskId, resultDigest);
        try {
            revealReceipt = revealCall.send();
        } catch (Exception e) {
            log.error("Failed to reveal [chainTaskId:{}, exception:{}]", chainTaskId, e.getMessage());
            e.printStackTrace();
            return null;
        }

        List<IexecHubABILegacy.TaskRevealEventResponse> revealEvents = getHubContract().getTaskRevealEvents(revealReceipt);

        IexecHubABILegacy.TaskRevealEventResponse revealEvent = null;
        if (revealEvents != null && !revealEvents.isEmpty()) {
            revealEvent = revealEvents.get(0);
        }

        if (revealEvent != null && revealEvent.log != null &&
                (!revealEvent.log.getType().equals(PENDING_RECEIPT_STATUS)
                        || isContributionStatusValidOnChainAfterPendingReceipt(chainTaskId, REVEALED))) {
            log.info("Contributed [chainTaskId:{}, resultDigest:{}, gasUsed:{}]",
                    chainTaskId, resultDigest, revealReceipt.getGasUsed());
            return revealEvent;
        }

        log.error("Failed to reveal [chainTaskId:{}]", chainTaskId);
        return null;
    }

    private long getWaitingTransactionCount() {
        return executor.getTaskCount() - 1 - executor.getCompletedTaskCount();
    }

    Optional<ChainContribution> getChainContribution(String chainTaskId) {
        return getChainContribution(chainTaskId, credentialsService.getCredentials().getAddress());
    }

    Optional<ChainAccount> getChainAccount() {
        return getChainAccount(credentialsService.getCredentials().getAddress());
    }

    public boolean hasEnoughGas() {
        return web3jService.hasEnoughGas(credentialsService.getCredentials().getAddress());
    }

    public long getLastBlock() {
        try {
            return web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send().getBlock().getNumber().longValue();
        } catch (IOException e) {
            log.error("GetLastBlock failed");
        }
        return 0;
    }

    private Boolean isContributionStatusValidOnChain(String chainTaskId, ChainContributionStatus chainContributionStatus) {
        Optional<ChainContribution> chainContribution = getChainContribution(chainTaskId);
        return chainContribution.isPresent() && chainContribution.get().getStatus().equals(chainContributionStatus);
    }

    private boolean isContributionStatusValidOnChainAfterPendingReceipt(String chainTaskId, ChainContributionStatus chainContributionStatus) {
        long maxWaitingTime = web3jService.getMaxWaitingTimeWhenPendingReceipt();

        final long startTime = System.currentTimeMillis();
        long duration = 0;
        while (duration < maxWaitingTime) {
            try {
                if (isContributionStatusValidOnChain(chainTaskId, chainContributionStatus)) {
                    return true;
                }
                Thread.sleep(500);
            } catch (InterruptedException e) {
                log.error("Error in checking the latest block number");
            }
            duration = System.currentTimeMillis() - startTime;
        }
        return false;
    }


}
