package com.iexec.worker.feign;

import java.util.List;

import com.iexec.common.chain.ContributionAuthorization;
import com.iexec.common.disconnection.InterruptedReplicateModel;

import feign.FeignException;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;


@FeignClient(
    name = "TaskClient",
    url = "http://${core.host}:${core.port}"
)
public interface TaskClient {

    // @GetMapping("/tasks/available")
    // ContributionAuthorization getAvailableReplicate(
    //         @RequestParam(name = "blockNumber") long blockNumber,
    //         @RequestHeader("Authorization") String bearerToken
    // ) throws FeignException;

    // @GetMapping("/tasks/interrupted")
    // List<InterruptedReplicateModel> getInterruptedReplicates(
    //         @RequestParam(name = "blockNumber") long blockNumber,
    //         @RequestHeader("Authorization") String bearerToken
    // ) throws FeignException;
}