/**
 * (C) Copyright IBM Corp. 2019
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.watsonhealth.fhir.model.resource;

import javax.annotation.Generated;

import com.ibm.watsonhealth.fhir.model.type.Base64Binary;
import com.ibm.watsonhealth.fhir.model.type.Code;
import com.ibm.watsonhealth.fhir.model.type.Id;
import com.ibm.watsonhealth.fhir.model.type.Meta;
import com.ibm.watsonhealth.fhir.model.type.Reference;
import com.ibm.watsonhealth.fhir.model.type.Uri;
import com.ibm.watsonhealth.fhir.model.util.ValidationSupport;
import com.ibm.watsonhealth.fhir.model.visitor.Visitor;

/**
 * <p>
 * A resource that represents the data of a single raw artifact as digital content accessible in its native format. A 
 * Binary resource can contain any content, whether text, image, pdf, zip archive, etc.
 * </p>
 */
@Generated("com.ibm.watsonhealth.fhir.tools.CodeGenerator")
public class Binary extends Resource {
    private final Code contentType;
    private final Reference securityContext;
    private final Base64Binary data;

    private Binary(Builder builder) {
        super(builder);
        this.contentType = ValidationSupport.requireNonNull(builder.contentType, "contentType");
        this.securityContext = builder.securityContext;
        this.data = builder.data;
    }

    /**
     * <p>
     * MimeType of the binary content represented as a standard MimeType (BCP 13).
     * </p>
     * 
     * @return
     *     An immutable object of type {@link Code}.
     */
    public Code getContentType() {
        return contentType;
    }

    /**
     * <p>
     * This element identifies another resource that can be used as a proxy of the security sensitivity to use when deciding 
     * and enforcing access control rules for the Binary resource. Given that the Binary resource contains very few elements 
     * that can be used to determine the sensitivity of the data and relationships to individuals, the referenced resource 
     * stands in as a proxy equivalent for this purpose. This referenced resource may be related to the Binary (e.g. Media, 
     * DocumentReference), or may be some non-related Resource purely as a security proxy. E.g. to identify that the binary 
     * resource relates to a patient, and access should only be granted to applications that have access to the patient.
     * </p>
     * 
     * @return
     *     An immutable object of type {@link Reference}.
     */
    public Reference getSecurityContext() {
        return securityContext;
    }

    /**
     * <p>
     * The actual content, base64 encoded.
     * </p>
     * 
     * @return
     *     An immutable object of type {@link Base64Binary}.
     */
    public Base64Binary getData() {
        return data;
    }

    @Override
    public void accept(java.lang.String elementName, Visitor visitor) {
        if (visitor.preVisit(this)) {
            visitor.visitStart(elementName, this);
            if (visitor.visit(elementName, this)) {
                // visit children
                accept(id, "id", visitor);
                accept(meta, "meta", visitor);
                accept(implicitRules, "implicitRules", visitor);
                accept(language, "language", visitor);
                accept(contentType, "contentType", visitor);
                accept(securityContext, "securityContext", visitor);
                accept(data, "data", visitor);
            }
            visitor.visitEnd(elementName, this);
            visitor.postVisit(this);
        }
    }

    @Override
    public Builder toBuilder() {
        Builder builder = new Builder(contentType);
        builder.id = id;
        builder.meta = meta;
        builder.implicitRules = implicitRules;
        builder.language = language;
        builder.securityContext = securityContext;
        builder.data = data;
        return builder;
    }

    public static Builder builder(Code contentType) {
        return new Builder(contentType);
    }

    public static class Builder extends Resource.Builder {
        // required
        private final Code contentType;

        // optional
        private Reference securityContext;
        private Base64Binary data;

        private Builder(Code contentType) {
            super();
            this.contentType = contentType;
        }

        /**
         * <p>
         * The logical id of the resource, as used in the URL for the resource. Once assigned, this value never changes.
         * </p>
         * 
         * @param id
         *     Logical id of this artifact
         * 
         * @return
         *     A reference to this Builder instance.
         */
        @Override
        public Builder id(Id id) {
            return (Builder) super.id(id);
        }

        /**
         * <p>
         * The metadata about the resource. This is content that is maintained by the infrastructure. Changes to the content 
         * might not always be associated with version changes to the resource.
         * </p>
         * 
         * @param meta
         *     Metadata about the resource
         * 
         * @return
         *     A reference to this Builder instance.
         */
        @Override
        public Builder meta(Meta meta) {
            return (Builder) super.meta(meta);
        }

        /**
         * <p>
         * A reference to a set of rules that were followed when the resource was constructed, and which must be understood when 
         * processing the content. Often, this is a reference to an implementation guide that defines the special rules along 
         * with other profiles etc.
         * </p>
         * 
         * @param implicitRules
         *     A set of rules under which this content was created
         * 
         * @return
         *     A reference to this Builder instance.
         */
        @Override
        public Builder implicitRules(Uri implicitRules) {
            return (Builder) super.implicitRules(implicitRules);
        }

        /**
         * <p>
         * The base language in which the resource is written.
         * </p>
         * 
         * @param language
         *     Language of the resource content
         * 
         * @return
         *     A reference to this Builder instance.
         */
        @Override
        public Builder language(Code language) {
            return (Builder) super.language(language);
        }

        /**
         * <p>
         * This element identifies another resource that can be used as a proxy of the security sensitivity to use when deciding 
         * and enforcing access control rules for the Binary resource. Given that the Binary resource contains very few elements 
         * that can be used to determine the sensitivity of the data and relationships to individuals, the referenced resource 
         * stands in as a proxy equivalent for this purpose. This referenced resource may be related to the Binary (e.g. Media, 
         * DocumentReference), or may be some non-related Resource purely as a security proxy. E.g. to identify that the binary 
         * resource relates to a patient, and access should only be granted to applications that have access to the patient.
         * </p>
         * 
         * @param securityContext
         *     Identifies another resource to use as proxy when enforcing access control
         * 
         * @return
         *     A reference to this Builder instance.
         */
        public Builder securityContext(Reference securityContext) {
            this.securityContext = securityContext;
            return this;
        }

        /**
         * <p>
         * The actual content, base64 encoded.
         * </p>
         * 
         * @param data
         *     The actual content
         * 
         * @return
         *     A reference to this Builder instance.
         */
        public Builder data(Base64Binary data) {
            this.data = data;
            return this;
        }

        @Override
        public Binary build() {
            return new Binary(this);
        }
    }
}