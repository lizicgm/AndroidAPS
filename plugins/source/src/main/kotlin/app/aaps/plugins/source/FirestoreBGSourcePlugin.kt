package app.aaps.plugins.source

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.objects.workflow.LoggingWorker
import android.annotation.SuppressLint
import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.android.HasAndroidInjector
import javax.inject.Inject
import javax.inject.Singleton
import com.google.firebase.Firebase
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QueryDocumentSnapshot
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.plugin.PluginBase
import io.reactivex.rxjava3.disposables.CompositeDisposable

@Singleton
class FirestorePlugin @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    config: Config
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(BGSourceFragment::class.java.name)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_firebase_bg)
        .preferencesId(R.xml.pref_firebase)
        .pluginName(R.string.firestore_bg)
        .shortName(R.string.firestore_bg_short)
        .description(R.string.description_source_firestore_client)
        .alwaysEnabled(config.FIRESTORE)
        .setDefault(config.FIRESTORE),
    aapsLogger, rh
), BgSource {

    private var listener: ListenerRegistration? = null
    private val disposable = CompositeDisposable()
    internal val firestore: FirebaseFirestore by lazy {
        Firebase.firestore.apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .build()
        }
    }

    override fun onStart() {
        super.onStart()
        aapsLogger.info(LTag.FIRESTORE, "starting listener")
        if (listener != null) {
            listener?.remove()
        }

        val cal = Calendar.getInstance()
        cal.add(Calendar.MINUTE, -5)
        val startDateMs = cal.timeInMillis

        listener = firestore.collection("entries")
            .whereGreaterThan("date", startDateMs)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    aapsLogger.error(LTag.FIRESTORE, "error listening to firebase", e)
                    return@addSnapshotListener
                }

                for (dc in snapshots!!.documentChanges) {
                    if (dc.type == DocumentChange.Type.ADDED) {
                        aapsLogger.info(
                            LTag.FIRESTORE,
                            "got new snapshot event ${dc.type}  ${dc.document.data}"
                        )
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

    @SuppressLint("CheckResult")
    private fun handleNewData(document: QueryDocumentSnapshot) {
        if (!isEnabled()) return
        
        aapsLogger.debug(LTag.FIRESTORE, "adding entry")
        val data = document.data
        aapsLogger.debug(LTag.FIRESTORE, "Received Firestore Data: $data")
        
        val glucoseValues = mutableListOf<GV>()
        glucoseValues += GV(
            timestamp = data["date"].toString().toLong(),
            value = data["sgv"].toString().toDouble(),
            raw = 0.0,
            noise = null,
            trendArrow = TrendArrow.fromString(data["direction"].toString()),
            sourceSensor = SourceSensor.fromString(data["device"].toString())
        )
        
        persistenceLayer.insertCgmSourceData(Sources.Firestore, glucoseValues, emptyList(), null)
            .doOnError { aapsLogger.error(LTag.FIRESTORE, "Error inserting CGM data", it) }
            .subscribe()
            .let { disposable.add(it) }
    }

    // cannot be inner class because of needed injection
    class FirebaseWorker(
        context: Context,
        params: WorkerParameters
    ) : LoggingWorker(context, params, Dispatchers.IO) {

        @Inject lateinit var injector: HasAndroidInjector
        @Inject lateinit var firestorePlugin: FirestorePlugin
        @Inject lateinit var persistenceLayer: PersistenceLayer

        @SuppressLint("CheckResult")
        override suspend fun doWorkAndLog(): Result {
            var ret = Result.success()

            if (!firestorePlugin.isEnabled()) return Result.success(workDataOf("Result" to "Plugin not enabled"))
            
            val data = inputData.getString("data")
            aapsLogger.debug(LTag.FIRESTORE, "Received Firestore Data: $data")
            
            if (!data.isNullOrEmpty()) {
                try {
                    val glucoseValues = mutableListOf<GV>()
                    // Parse the data string as needed - adjust based on your data format
                    // For now, assuming it's a single glucose reading
                    val document = firestorePlugin.firestore.collection("entries").document(data).get().await()
                    val docData = document.data ?: return Result.failure(workDataOf("Error" to "No data in document"))
                    
                    glucoseValues += GV(
                        timestamp = docData["date"].toString().toLong(),
                        value = docData["sgv"].toString().toDouble(),
                        raw = 0.0,
                        noise = null,
                        trendArrow = TrendArrow.fromString(docData["direction"].toString()),
                        sourceSensor = SourceSensor.fromString(docData["device"].toString())
                    )
                    
                    persistenceLayer.insertCgmSourceData(Sources.Firestore, glucoseValues, emptyList(), null)
                        .doOnError { 
                            aapsLogger.error(LTag.FIRESTORE, "Error inserting CGM data", it)
                            ret = Result.failure(workDataOf("Error" to it.toString()))
                        }
                        .blockingGet()
                } catch (e: Exception) {
                    aapsLogger.error("Error while processing Firestore data", e)
                    ret = Result.failure(workDataOf("Error" to e.toString()))
                }
            }
            return ret
        }
    }
}
