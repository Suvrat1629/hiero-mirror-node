// SPDX-License-Identifier: Apache-2.0

package org.hiero.mirror.web3.viewmodel;

public record ContractCallResponse(String result, Long gasUsed) {
    public ContractCallResponse(String result) {
        this(result, null);
    }
}
