package org.nexus.interop.contract

import android.os.Parcel
import android.os.Parcelable

/**
 * Metadata contract representing a distinct interoperable capability.
 */
data class NexusCapability(
    val capabilityId: String,               // e.g. "nexus.text.uppercase"
    val displayName: String,                // e.g. "Convert to Uppercase"
    val description: String,                // e.g. "Transforms plain text into uppercase"
    val version: String = "1.0.0",          // Semantic capability version (e.g. 1.0.0)
    val category: String = "text",          // Functional domain
    val inputMimeType: String = "text/plain",
    val outputMimeType: String = "text/plain",
    val inputSchema: String = "text/plain", // Format description or JSON schema
    val outputSchema: String = "text/plain",// Format description or JSON schema
    val requiredPermissions: List<String> = emptyList(),
    val maxPayloadSizeBytes: Long = 65536L, // 64 KB Binder safe limit
    val timeoutMs: Long = 5000L,            // Recommended timeout
    val isAvailable: Boolean = true,
    // Stamped strictly by the Framework from kernel Binder credentials:
    val providerPackage: String = "",
    val providerUid: Int = 0
) : Parcelable {

    constructor(parcel: Parcel) : this(
        capabilityId = parcel.readString() ?: "",
        displayName = parcel.readString() ?: "",
        description = parcel.readString() ?: "",
        version = parcel.readString() ?: "1.0.0",
        category = parcel.readString() ?: "text",
        inputMimeType = parcel.readString() ?: "text/plain",
        outputMimeType = parcel.readString() ?: "text/plain",
        inputSchema = parcel.readString() ?: "text/plain",
        outputSchema = parcel.readString() ?: "text/plain",
        requiredPermissions = parcel.createStringArrayList() ?: emptyList(),
        maxPayloadSizeBytes = parcel.readLong(),
        timeoutMs = parcel.readLong(),
        isAvailable = parcel.readByte() != 0.toByte(),
        providerPackage = parcel.readString() ?: "",
        providerUid = parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(capabilityId)
        parcel.writeString(displayName)
        parcel.writeString(description)
        parcel.writeString(version)
        parcel.writeString(category)
        parcel.writeString(inputMimeType)
        parcel.writeString(outputMimeType)
        parcel.writeString(inputSchema)
        parcel.writeString(outputSchema)
        parcel.writeStringList(requiredPermissions)
        parcel.writeLong(maxPayloadSizeBytes)
        parcel.writeLong(timeoutMs)
        parcel.writeByte(if (isAvailable) 1 else 0)
        parcel.writeString(providerPackage)
        parcel.writeInt(providerUid)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<NexusCapability> {
        override fun createFromParcel(parcel: Parcel): NexusCapability = NexusCapability(parcel)
        override fun newArray(size: Int): Array<NexusCapability?> = arrayOfNulls(size)
    }
}
