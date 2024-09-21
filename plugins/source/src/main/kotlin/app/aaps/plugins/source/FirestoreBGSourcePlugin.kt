package app.aaps.plugins.source

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.plugin.PluginType
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.utils.T
import app.aaps.database.entities.GlucoseValue
import app.aaps.database.impl.AppRepository
import app.aaps.database.impl.transactions.CgmSourceTransaction
import app.aaps.database.transactions.TransactionGlucoseValue
import dagger.android.HasAndroidInjector
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import javax.inject.Singleton
import com.google.firebase.Firebase
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.QueryDocumentSnapshot
import java.util.Calendar

@Singleton
class FirestoreBGSourcePlugin @Inject constructor(
    injector: HasAndroidInjector,
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    private val repository: AppRepository,
    private val config: Config
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(BGSourceFragment::class.java.name)
        .pluginIcon(app.aaps.core.main.R.drawable.ic_firebase_bg)
        .preferencesId(R.xml.pref_firebase)
        .pluginName(R.string.firestore_bg)
        .shortName(R.string.firestore_bg_short)
        .description(R.string.description_source_firestore_client)
        .setDefault(config.FIRESTORE),
    aapsLogger, rh, injector
), BgSource {

    private var listener: ListenerRegistration? = null

    private val disposable = CompositeDisposable()

    override fun advancedFilteringSupported(): Boolean = true

    override fun onStart() {
        super.onStart()
        aapsLogger.info(LTag.FIRESTORE, "starting listener")
        if (listener != null) {
            listener?.remove()
        }

        val cal = Calendar.getInstance()
        cal.add(Calendar.MINUTE, -5)
        val startDateMs = cal.timeInMillis

        listener = Firebase.firestore.collection("entries").whereGreaterThan("date", startDateMs).addSnapshotListener { snapshots, e ->
                if (e != null) {
                    aapsLogger.error(LTag.FIRESTORE, "error listening to firebase", e)
                    return@addSnapshotListener
                }

                for (dc in snapshots!!.documentChanges) {
                    val type = dc.type
                    aapsLogger.info(LTag.FIRESTORE, "got new snapshot event $type  ${dc.document.data}")
                    if (type == DocumentChange.Type.ADDED) {
                        handleNewData(dc.document)

                    }
                }
            }

    }

    override fun onStop() {
        super.onStop()
        listener?.remove()
        listener = null
        disposable.clear()
    }

    private fun handleNewData(snapshot: QueryDocumentSnapshot) {
        if (!isEnabled()) return
        aapsLogger.debug(LTag.FIRESTORE, "adding entry")
        val data = snapshot.data
        val glucoseValues = mutableListOf<TransactionGlucoseValue>()
        glucoseValues += TransactionGlucoseValue(
            timestamp = data["date"].toString().toLong(),
            value = data["sgv"].toString().toDouble(),
            noise = null,
            raw = 0.0,
            trendArrow = GlucoseValue.TrendArrow.fromString(data["direction"].toString()),
            nightscoutId = data["identifier"].toString(),
            sourceSensor = GlucoseValue.SourceSensor.fromString(data["device"].toString()),
            isValid = true,
            utcOffset = T.mins(data["utcOffset"].toString().toLong() ?: 0L).msecs()
        )

        disposable += repository.runTransactionForResult(CgmSourceTransaction(glucoseValues, emptyList(), null))
            .subscribe({ savedValues ->
                           savedValues.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted bg $it") }
                       }, { aapsLogger.error(LTag.DATABASE, "Error while saving values from firebase", it) }
            )
    }
}
