package com.peterlaurence.trekme.viewmodel.mapcreate

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.SkuDetails
import com.peterlaurence.trekme.billing.ign.Billing
import com.peterlaurence.trekme.billing.ign.LicenseInfo
import com.peterlaurence.trekme.billing.ign.PersistenceStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

class IgnLicenseViewModel : ViewModel() {
    private val ignLicenseStatus = MutableLiveData<LicenseStatus>()
    private val ignLicenseDetails = MutableLiveData<IgnLicenseDetails>()

    fun getIgnLicensePurchaseStatus(billing: Billing) {
        viewModelScope.launch {
            /* First, check if we just need to acknowledge the purchase */
            val ackDone = billing.acknowledgeIgnLicense(this@IgnLicenseViewModel::onPurchaseAcknowledged)

            /* Otherwise, do normal checks */
            if (!ackDone) {
                billing.getIgnLicensePurchase().also {
                    ignLicenseStatus.postValue(if (it != null) LicenseStatus.PURCHASED else LicenseStatus.NOT_PURCHASED)
                }
            }
        }
    }

    fun getIgnLicenseInfo(billing: Billing) {
        viewModelScope.launch {
            try {
                val licenseDetails = billing.getIgnLicenseDetails()
                ignLicenseDetails.postValue(licenseDetails)
            } catch (e: ProductNotFoundException) {
                // something wrong on our side
                ignLicenseStatus.postValue(LicenseStatus.PURCHASED)
            } catch (e: IllegalStateException) {
                // can't check license info, so assume it's not valid
            } catch (e: NotSupportedException) {
                // TODO: alert the user that it can't buy the license and should ask for refund
            }
        }
    }

    fun buyLicense(billing: Billing) {
        val ignLicenseDetails = ignLicenseDetails.value
        if (ignLicenseDetails != null) {
            billing.launchBilling(ignLicenseDetails.skuDetails, this::onPurchaseAcknowledged, this::onPurchasePending)
        }
    }

    private fun onPurchasePending() {
        ignLicenseStatus.postValue(LicenseStatus.PENDING)
    }

    /**
     * This is the callback called when the IGN license is considered successfully bought.
     */
    private fun onPurchaseAcknowledged() {
        /* It's assumed that if this is called, it's a success */
        ignLicenseStatus.postValue(LicenseStatus.PURCHASED)

        /* Remember when (it's not exact but it doesn't matter) the license was bought */
        persistLicense(LicenseInfo(Date().time))
    }

    fun getIgnLicenseStatus(): LiveData<LicenseStatus> {
        return ignLicenseStatus
    }

    fun getIgnLicenseDetails(): LiveData<IgnLicenseDetails> {
        return ignLicenseDetails
    }

    /**
     * Persist licensing info in a custom way, as we need a relatively reliable way to
     * check the license while being offline. Indeed, even if a check using the cache of
     * the play store can help, the user could have cleared the cache and then there's
     * no remote way to do the necessary verifications. So the persistence of the license buy
     * date permits an alternate way of checking. */
    private fun persistLicense(licenseInfo: LicenseInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val persistenceStrategy = PersistenceStrategy()
            persistenceStrategy.persist(licenseInfo)
        }
    }
}

enum class LicenseStatus {
    PURCHASED, NOT_PURCHASED, PENDING
}

data class IgnLicenseDetails(val skuDetails: SkuDetails) {
    val price: String
        get() = skuDetails.price
}

class NotSupportedException : Exception()
class ProductNotFoundException : Exception()