package com.template.schema

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

/**
 * The family of schemas for MyCash.
 */
object MyCashSchema

/**
 * A MyCash schema.
 */
object MyCashSchemaV1 : MappedSchema(
        schemaFamily = MyCashSchema.javaClass,
        version = 1,
        mappedTypes = listOf(PersistentMyCash::class.java)) {
    @Entity
    @Table(name = "mycash_states")
    class PersistentMyCash(
            @Column(name = "issuer")
            var issuerName: String,

            @Column(name = "owner")
            var ownerName: String,

            @Column(name = "amount")
            var amount: Long,

            @Column(name = "currency_code", length = 3)
            var currencyCode: String
    ) : PersistentState() {
        // Required by Hibernate
        constructor(): this("", "", 0L, "")
    }
}