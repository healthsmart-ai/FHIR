/**
 * (C) Copyright IBM Corp. 2019
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.watsonhealth.fhir.model.path.function;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import com.ibm.watsonhealth.fhir.model.path.FHIRPathNode;
import com.ibm.watsonhealth.fhir.model.path.evaluator.FHIRPathEvaluator.Environment;

public class TailFunction extends FHIRPathAbstractFunction {
    @Override
    public String getName() {
        return "tail";
    }

    @Override
    public int getMinArity() {
        return 0;
    }

    @Override
    public int getMaxArity() {
        return 0;
    }
    
    @Override
    public Collection<FHIRPathNode> apply(Environment environment, Collection<FHIRPathNode> context, List<Collection<FHIRPathNode>> arguments) {
        return context.stream()
                .skip(1)
                .collect(Collectors.toList());
    }
}