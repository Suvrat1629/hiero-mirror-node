// SPDX-License-Identifier: Apache-2.0

package org.hiero.mirror.web3.service;

import com.google.common.base.Stopwatch;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Named;
import java.math.BigInteger;
import java.util.Objects;
import lombok.CustomLog;
import org.apache.tuweni.bytes.Bytes;
import org.hiero.mirror.web3.common.ContractCallContext;
import org.hiero.mirror.web3.evm.properties.EvmProperties;
import org.hiero.mirror.web3.service.model.ContractExecutionParameters;
import org.hiero.mirror.web3.service.model.ContractExecutionResult;
import org.hiero.mirror.web3.service.utils.BinaryGasEstimator;
import org.hiero.mirror.web3.throttle.ThrottleManager;
import org.hiero.mirror.web3.throttle.ThrottleProperties;

@CustomLog
@Named
public class ContractExecutionService extends ContractCallService {

    private final BinaryGasEstimator binaryGasEstimator;

    @SuppressWarnings("java:S107")
    public ContractExecutionService(
            MeterRegistry meterRegistry,
            BinaryGasEstimator binaryGasEstimator,
            RecordFileService recordFileService,
            ThrottleProperties throttleProperties,
            ThrottleManager throttleManager,
            EvmProperties evmProperties,
            TransactionExecutionService transactionExecutionService) {
        super(
                throttleManager,
                throttleProperties,
                meterRegistry,
                recordFileService,
                evmProperties,
                transactionExecutionService);
        this.binaryGasEstimator = binaryGasEstimator;
    }

    /**
     * Backwards compatible method returning only the result hex string.
     */
    public String processCall(final ContractExecutionParameters params) {
        return processCallWithGas(params).result();
    }

    /**
     * New API that returns both the result and the actual gas used by the execution.
     */
    public ContractExecutionResult processCallWithGas(final ContractExecutionParameters params) {
        return ContractCallContext.run(ctx -> {
            var stopwatch = Stopwatch.createStarted();
            var stringResult = "";
            long gasUsed = 0L;

            try {
                updateGasLimitMetric(params);

                Bytes result;
                if (params.isEstimate()) {
                    result = estimateGas(params, ctx);
                } else {
                    final var ethCallTxnResult = callContract(params, ctx);
                    result = Objects.requireNonNullElse(
                            Bytes.fromHexString(ethCallTxnResult.contractCallResult()), Bytes.EMPTY);
                    gasUsed = ethCallTxnResult.gasUsed();
                }

                stringResult = result.toHexString();

                if (params.isEstimate()) {
                    // estimateGas returned the estimated gas as an unsigned long encoded in hex payload
                    if (stringResult != null && stringResult.startsWith("0x") && stringResult.length() > 2) {
                        gasUsed = new BigInteger(stringResult.substring(2), 16).longValue();
                    } else {
                        gasUsed = 0L;
                    }
                }
            } finally {
                log.debug("Processed request {} in {}: {}", params, stopwatch, stringResult);
            }

            return new ContractExecutionResult(stringResult, gasUsed);
        });
    }

    /**
     * This method estimates the amount of gas required to execute a smart contract function. The estimation process
     * involves two steps:
     * <p>
     * 1. Firstly, a call is made with user inputted gas value (default and maximum value for this parameter is 15
     * million) to determine if the call estimation is possible. This step is intended to quickly identify any issues
     * that would prevent the estimation from succeeding.
     * <p>
     * 2. Finally, if the first step is successful, a binary search is initiated. The lower bound of the search is the
     * gas used in the first step, while the upper bound is the inputted gas parameter.
     */
    private Bytes estimateGas(final ContractExecutionParameters params, final ContractCallContext context) {
        final var processingResult = callContract(params, context);
        final var gasUsedByInitialCall = processingResult.gasUsed();

        // sanity check ensuring gasUsed is always lower than the inputted one
        if (gasUsedByInitialCall >= params.getGas()) {
            return Bytes.ofUnsignedLong(gasUsedByInitialCall);
        }

        final var status = ResponseCodeEnum.SUCCESS.toString();
        final var estimatedGas = binaryGasEstimator.search(
                (totalGas, iterations) -> updateMetrics(params, totalGas, iterations, status),
                gas -> doProcessCall(params, gas, true),
                gasUsedByInitialCall,
                params.getGas());

        return Bytes.ofUnsignedLong(estimatedGas);
    }
}
