package org.nexus.interop.contract

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/**
 * Request envelope marshaled across process boundaries.
 */
data class NexusRequest(
    val requestId: String,                    // Unique UUID for transaction correlation
    val capabilityId: String,                 // Target capability to invoke
    val payload: String,                      // Input payload string (bounded to 64 KB)
    val timeoutMs: Long = 5000L,              // Client execution timeout limit
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val extras: Bundle = Bundle(),
    // Populated strictly by the Framework from kernel Binder credentials:
    val verifiedClientPackage: String = "",
    val verifiedClientUid: Int = 0
) : Parcelable {

    constructor(parcel: Parcel) : this(
        requestId = parcel.readString() ?: "",
        capabilityId = parcel.readString() ?: "",
        payload = parcel.readString() ?: "",
        timeoutMs = parcel.readLong(),
        timestampEpochMs = parcel.readLong(),
        extras = parcel.readBundle(NexusRequest::class.java.classLoader) ?: Bundle(),
        verifiedClientPackage = parcel.readString() ?: "",
        verifiedClientUid = parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(requestId)
        parcel.writeString(capabilityId)
        parcel.writeString(payload)
        parcel.writeLong(timeoutMs)
        parcel.writeLong(timestampEpochMs)
        parcel.writeBundle(extras)
        parcel.writeString(verifiedClientPackage)
        parcel.writeInt(verifiedClientUid)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<NexusRequest> {
        override fun createFromParcel(parcel: Parcel): NexusRequest = NexusRequest(parcel)
        override fun newArray(size: Int): Array<NexusRequest?> = arrayOfNulls(size)
    }
}
