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
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.QueryDocumentSnapshot
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.plugin.PluginBase
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

@Singleton
class FirestorePlugin @Inject constructor(
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

    class FirebaseWorker(
        context: Context,
        params: WorkerParameters
    ) : LoggingWorker(context, params, Dispatchers.IO) {

        @Inject lateinit var injector: HasAndroidInjector
        @Inject lateinit var firebasePlugin: FirestorePlugin
        @Inject lateinit var persistenceLayer: PersistenceLayer

        private val disposable = CompositeDisposable()

        @SuppressLint("CheckResult")
        private fun doWorkAndLog(document: QueryDocumentSnapshot) {
            if (!firebasePlugin.isEnabled()) return

            val data = document.data
            aapsLogger.debug(LTag.BGSOURCE, "Received Firestore Data: $data")
            val glucoseValues = mutableListOf<GV>()
            glucoseValues += GV(
                timestamp = data["date"].toString().toLong(),
                value = data["sgv"].toString().toDouble(),
                raw = 0.0,
                noise = null,
                trendArrow = TrendArrow.fromString(data["direction"].toString()),
                sourceSensor = SourceSensor.fromString(data["device"].toString())
            )
            persistenceLayer.insertCgmSourceData(Sources.Firebase, glucoseValues, emptyList(), null)
                .doOnError { aapsLogger.error(LTag.FIRESTORE, "Error inserting CGM data", it) }
                .subscribe()
                .let { disposable.add(it) }
        }

        override suspend fun doWorkAndLog(): Result {
            if (!firebasePlugin.isEnabled()) return Result.success(workDataOf("Result" to "Plugin not enabled"))

            aapsLogger.info(LTag.FIRESTORE, "starting listener")

            suspendCancellableCoroutine<Unit> { continuation ->
                val cal = Calendar.getInstance()
                cal.add(Calendar.MINUTE, -5)
                val startDateMs = cal.timeInMillis

                val listener = Firebase.firestore.collection("entries")
                    .whereGreaterThan("date", startDateMs)
                    .addSnapshotListener { snapshots, e ->
                        if (e != null) {
                            aapsLogger.error(LTag.FIRESTORE, "error listening to firebase", e)
                            if (continuation.isActive) {
                                continuation.resumeWithException(e)
                            }
                            return@addSnapshotListener
                        }

                        for (dc in snapshots!!.documentChanges) {
                            if (dc.type == DocumentChange.Type.ADDED) {
                                aapsLogger.info(
                                    LTag.FIRESTORE,
                                    "got new snapshot event ${dc.type}  ${dc.document.data}"
                                )
                                doWorkAndLog(dc.document)
                            }
                        }
                    }

                continuation.invokeOnCancellation {
                    aapsLogger.info(LTag.FIRESTORE, "stopping listener")
                    listener.remove()
                    disposable.clear()
                }
            }
            return Result.success()
        }
    }
}
