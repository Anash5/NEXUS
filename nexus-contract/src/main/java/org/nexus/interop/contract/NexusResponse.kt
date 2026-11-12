package org.nexus.interop.contract

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/**
 * Standardized response envelope returned to callers.
 */
data class NexusResponse(
    val requestId: String,
    val isSuccess: Boolean,
    val statusCode: Int,
    val resultData: String?,
    val errorMessage: String? = null,
    val executionTimeMs: Long = 0L,
    val verifiedProviderPackage: String = "",
    val metadata: Bundle = Bundle()
) : Parcelable {

    constructor(parcel: Parcel) : this(
        requestId = parcel.readString() ?: "",
        isSuccess = parcel.readByte() != 0.toByte(),
        statusCode = parcel.readInt(),
        resultData = parcel.readString(),
        errorMessage = parcel.readString(),
        executionTimeMs = parcel.readLong(),
        verifiedProviderPackage = parcel.readString() ?: "",
        metadata = parcel.readBundle(NexusResponse::class.java.classLoader) ?: Bundle()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(requestId)
        parcel.writeByte(if (isSuccess) 1 else 0)
        parcel.writeInt(statusCode)
        parcel.writeString(resultData)
        parcel.writeString(errorMessage)
        parcel.writeLong(executionTimeMs)
        parcel.writeString(verifiedProviderPackage)
        parcel.writeBundle(metadata)
    }

    override fun describeContents(): Int = 0

    companion object {
        const val STATUS_SUCCESS = 200
        const val STATUS_BAD_REQUEST = 400
        const val STATUS_PERMISSION_DENIED = 403
        const val STATUS_CAPABILITY_NOT_FOUND = 404
        const val STATUS_TIMEOUT = 408
        const val STATUS_DUPLICATE_REQUEST = 409
        const val STATUS_PROVIDER_DEACTIVATED = 410
        const val STATUS_LEASE_EXPIRED = 412
        const val STATUS_PAYLOAD_TOO_LARGE = 413
        const val STATUS_EXECUTION_FAILED = 500
        const val STATUS_PROVIDER_CRASHED = 502
        const val STATUS_PROVIDER_DISCONNECTED = 503

        @JvmField
        val CREATOR = object : Parcelable.Creator<NexusResponse> {
            override fun createFromParcel(parcel: Parcel): NexusResponse = NexusResponse(parcel)
            override fun newArray(size: Int): Array<NexusResponse?> = arrayOfNulls(size)
        }
    }
}
