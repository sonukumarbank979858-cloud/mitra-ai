package com.sonuclasses.mitra.tools

/** Payment / UPI / banking apps Mitra must never open or automate. */
object Safety {
    private val BLOCKED_PACKAGES = setOf(
        "com.phonepe.app", "net.one97.paytm", "com.paytmmoney", "com.google.android.apps.nbu.paisa.user",
        "in.org.npci.upiapp", "com.dreamplug.androidapp", "com.mobikwik_new", "com.freecharge.android",
        "in.amazon.mShop.android.shopping.pay", "com.whatsapp.payments", "com.sbi.lotusintouch",
        "com.sbi.SBIFreedomPlus", "com.sbi.upi", "com.csam.icici.bank.imobile", "com.icicibank.pockets",
        "com.snapwork.hdfc", "com.hdfcbank.payzapp", "com.hdfc.retail.banking", "com.axis.mobile",
        "com.msf.kbank.mobile", "com.kotak811mobilebankingapp.instantsavingsupiscanandpayrecharge",
        "com.bankofbaroda.mconnect", "com.bob.bobworld", "com.infrasofttech.indianBank", "com.pnb.pnbone",
        "com.canarabank.mobility", "com.unionbank.ecommerce.mobile.android", "com.idfcfirstbank.optimus",
        "com.yesbank.yesmobile", "com.indusind.mobile", "com.fss.ucobank", "com.cub.wallet.gui",
        "com.rblbank.mobank", "com.jio.myjio.jiopay", "money.super.payments", "com.navi.android",
        "com.bharatpe.app", "com.mipay.wallet.in", "com.samsung.android.spay", "com.google.android.apps.walletnfcrel",
        "com.airtel.money", "com.myairtelapp.payments", "com.cred.club", "com.slice", "com.fampay.in",
        "com.zerodha.kite3", "com.nextbillion.groww", "com.upstox.pro", "com.angelbroking.angelapp"
    )
    private val BLOCKED_WORDS = listOf(
        "bank", "upi", "paytm", "phonepe", "gpay", "google pay", "bhim", "wallet", "pay ", "payzapp",
        "बैंक", "पेमेंट", "भुगतान", "यूपीआई", "पेटीएम", "फोनपे"
    )

    fun isBlockedPackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val p = pkg.lowercase()
        if (BLOCKED_PACKAGES.any { it.lowercase() == p }) return true
        return p.contains("bank") || p.contains(".upi") || p.contains("paytm") || p.contains("phonepe")
    }

    fun isBlockedName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.lowercase() + " "
        return BLOCKED_WORDS.any { n.contains(it) }
    }

    const val REFUSAL = "माफ़ कीजिए, सुरक्षा के लिए मैं पेमेंट, UPI या बैंकिंग ऐप्स में कुछ नहीं करता। यह काम आप खुद करें।"
}
