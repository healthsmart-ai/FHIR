/**
 * (C) Copyright IBM Corp. 2019
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.watsonhealth.fhir.model.path.function;

import static com.ibm.watsonhealth.fhir.model.util.FHIRUtil.REFERENCE_PATTERN;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;

import com.ibm.watsonhealth.fhir.model.path.FHIRPathNode;
import com.ibm.watsonhealth.fhir.model.path.FHIRPathResourceNode;
import com.ibm.watsonhealth.fhir.model.path.FHIRPathTree;
import com.ibm.watsonhealth.fhir.model.path.FHIRPathType;
import com.ibm.watsonhealth.fhir.model.path.evaluator.FHIRPathEvaluator.Environment;
import com.ibm.watsonhealth.fhir.model.resource.DomainResource;
import com.ibm.watsonhealth.fhir.model.resource.Resource;
import com.ibm.watsonhealth.fhir.model.type.Reference;

public class ResolveFunction extends FHIRPathAbstractFunction {
    private static final int RESOURCE_TYPE = 4;

    @Override
    public String getName() {
        return "resolve";
    }

    @Override
    public int getMinArity() {
        return 0;
    }

    @Override
    public int getMaxArity() {
        return 0;
    }
    
    /**
     * For each item in the collection, if it is a string that is a uri (or canonical or url), locate the target of the 
     * reference, and add it to the resulting collection. If the item does not resolve to a resource, the item is ignored 
     * and nothing is added to the output collection. The items in the collection may also represent a Reference, in which 
     * case the Reference.reference is resolved.
     * 
     * This method creates a "proxy" resource node that is a placeholder for the actual resource, thus allowing for the 
     * FHIRPath evaluator to perform type checking on the result of the resolve function. For example:
     * 
     *     Observation.subject.where(resolve() is Patient)
     *     
     * If the resource type cannot be inferred from the reference URL or type, then FHIR_UNKNOWN_RESOURCE_TYPE is used.
     * 
     * Type checking on FHIR_UNKNOWN_RESOURCE_TYPE will always return 'true'.
     * 
     * @param environment
     *     the evaluation environment
     * @param context
     *     the current evaluation context
     * @param arguments
     *     the arguments for this function
     * @return
     *     the result of the function applied to the context and arguments
     */
    public Collection<FHIRPathNode> apply(Environment environment, Collection<FHIRPathNode> context, List<Collection<FHIRPathNode>> arguments) {
        Collection<FHIRPathNode> result = new ArrayList<>();
        for (FHIRPathNode node : context) {
            if (node.isElementNode() && node.asElementNode().element().is(Reference.class)) {
                Reference reference = node.asElementNode().element().as(Reference.class);
                
                String referenceReference = getReferenceReference(reference);
                String referenceType = getReferenceType(reference);
                
                if (referenceReference == null && referenceType == null) {
                    continue;
                }
                
                String resourceType = null;
                
                if (referenceReference != null) {
                    if (referenceReference.startsWith("#")) {
                        // internal fragment reference
                        resourceType = resolveInternalFragmentReference(environment.getTree(), referenceReference);
                    } else {
                        Matcher matcher = REFERENCE_PATTERN.matcher(referenceReference);
                        if (matcher.matches()) {
                            resourceType = matcher.group(RESOURCE_TYPE);                        
                            if (referenceType != null && !resourceType.equals(referenceType)) {
                                throw new IllegalArgumentException("resource type found in reference URL does not match reference type");
                            }
                        }
                    }
                }
                
                if (resourceType == null) {
                    resourceType = referenceType;
                }
                
                FHIRPathType type = (resourceType != null) ? FHIRPathType.from(resourceType) : FHIRPathType.FHIR_UNKNOWN_RESOURCE_TYPE;
                                
                result.add(FHIRPathResourceNode.proxy(type));
            }
        }
        return result;
    }
    
    private String resolveInternalFragmentReference(FHIRPathTree tree, String referenceReference) {
        if (tree != null) {
            FHIRPathNode root = tree.getRoot();
            if (root.isResourceNode()) {
                Resource resource = root.asResourceNode().resource();
                if ("#".equals(referenceReference)) {
                    return resource.getClass().getSimpleName();
                }
                String id = referenceReference.substring(1);
                if (resource instanceof DomainResource) {
                    DomainResource domainResource = (DomainResource) resource;
                    for (Resource contained : domainResource.getContained()) {
                        if (contained.getId() != null && 
                                contained.getId().getValue() != null && 
                                id.equals(contained.getId().getValue())) {
                            return contained.getClass().getSimpleName();
                        }
                    }
                }
            }
        }
        return null;
    }

    private String getReferenceReference(Reference reference) {
        if (reference.getReference() != null && reference.getReference().getValue() != null) {
            return reference.getReference().getValue();
        }
        return null;
    }
    
    private String getReferenceType(Reference reference) {
        if (reference.getType() != null && reference.getType().getValue() != null) {
            return reference.getType().getValue();
        }
        return null;
    }
}