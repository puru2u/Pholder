package com.dokidevs.pholder.info

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.provider.MediaStore
import android.view.MenuItem
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import com.dokidevs.dokilog.d
import com.dokidevs.dokilog.e
import com.dokidevs.pholder.PholderApplication.Companion.isDarkTheme
import com.dokidevs.pholder.R
import com.dokidevs.pholder.base.BaseActivity
import com.dokidevs.pholder.data.FileTag
import com.dokidevs.pholder.data.PholderTagUtil
import com.dokidevs.pholder.utils.REQUEST_GOOGLE_PLAY_SERVICES
import com.dokidevs.pholder.utils.shortSnackBar
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.gson.Gson
import com.tingyik90.snackprogressbar.SnackProgressBarManager
import kotlinx.android.synthetic.main.activity_info.*
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread
import java.util.*

/*--- InfoActivity ---*/
class InfoActivity : BaseActivity(), OnMapReadyCallback {

    /* companion object */
    companion object {

        /* intents */
        private const val FILE_TAG_JSON = "FILE_TAG_JSON"

        // newIntent
        fun newIntent(context: Context, fileTag: FileTag): Intent {
            val intent = Intent(context, InfoActivity::class.java)
            intent.putExtra(FILE_TAG_JSON, Gson().toJson(fileTag))
            return intent
        }

    }

    /* views */
    private val main by lazy { infoActivity_main }
    private val recyclerView by lazy { infoActivity_recyclerView }
    private val mapContainer by lazy { infoActivity_map_container }
    private val adapter = InfoListAdapter()

    /* parameters */
    private var latLng: LatLng? = null
    private var requestedPlayServiceUpdate = false
    private lateinit var fileTag: FileTag
    private lateinit var snackProgressBarManager: SnackProgressBarManager

    // onCreatePreAction
    override fun onCreatePreAction(savedInstanceState: Bundle?) {
        // Set theme
        if (isDarkTheme) {
            setTheme(R.style.AppTheme)
        } else {
            setTheme(R.style.AppTheme_Light)
        }
    }

    // onCreateAction
    override fun onCreateAction(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_info)
        snackProgressBarManager = SnackProgressBarManager(main)
        recyclerView.isNestedScrollingEnabled = false
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val fileTagJson = intent.getStringExtra(FILE_TAG_JSON)
        fileTag = Gson().fromJson(fileTagJson, FileTag::class.java)
        if (fileTag.checkExist()) {
            setFileName()
            setParentPath()
            setDateTime()
            setSize()
            setDuration()
            setAdapter()
            setLocation()
        } else {
            // In case file does not exist anymore after Android killed this activity due to memory
            finish()
        }
    }

    // setFileName
    private fun setFileName() {
        val infoSet = InfoListAdapter.InfoSet()
        if (fileTag.isImage()) {
            infoSet.resId = R.drawable.ic_image_white_24dp
        } else {
            infoSet.resId = R.drawable.ic_video_white_24dp
        }
        infoSet.textPrimary = fileTag.getFileNameWithExtension()
        adapter.addInfo(infoSet)
    }

    // setParentPath
    private fun setParentPath() {
        val infoSet = InfoListAdapter.InfoSet(
            R.drawable.ic_folder_white_24dp,
            PholderTagUtil.getRelativePathFromPublicRoot(fileTag.parentPath)
        )
        adapter.addInfo(infoSet)
    }

    // setDateTime
    private fun setDateTime() {
        val infoSet = InfoListAdapter.InfoSet(R.drawable.ic_calendar_white_24dp)
        infoSet.textPrimary = PholderTagUtil.millisToDate(fileTag.dateTaken)
        infoSet.textSecondary = PholderTagUtil.millisToTime(fileTag.dateTaken)
        adapter.addInfo(infoSet)
    }

    // setSize
    private fun setSize() {
        val file = fileTag.toFile()
        if (file.exists()) {
            val infoSet = InfoListAdapter.InfoSet(R.drawable.ic_aspect_ratio_white_24dp)
            if (fileTag.isImage()) {
                // Get dimensions
                val options = BitmapFactory.Options()
                // Set this to returns null bitmap, but sizes are in the options variable
                options.inJustDecodeBounds = true
                BitmapFactory.decodeFile(fileTag.getFilePath(), options)
                infoSet.textPrimary = "${options.outWidth} x ${options.outHeight}"
            } else {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(fileTag.getFilePath())
                val width = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                val height =
                    Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                retriever.release()
                infoSet.textPrimary = "$width x $height"
            }
            // Get file size
            infoSet.textSecondary = PholderTagUtil.getFileSize(file)
            adapter.addInfo(infoSet)
        }
    }

    // setDuration
    private fun setDuration() {
        if (fileTag.isVideo()) {
            val infoSet = InfoListAdapter.InfoSet(R.drawable.ic_play_circle_outline_white_24dp)
            infoSet.textPrimary = PholderTagUtil.videoMillisToDurationWords(applicationContext, fileTag.duration)
            adapter.addInfo(infoSet)
        }
    }

    // setAdapter
    private fun setAdapter() {
        recyclerView.adapter = adapter
    }

    // setLocation
    private fun setLocation() {
        doAsync {
            if (latLng == null) {
                // Get latLng from MediaStore for others, or when exif fails
                val cursor = contentResolver.query(
                    PholderTagUtil.getExternalContentUri(fileTag.getFilePath()),
                    // Column name is same for image and video
                    arrayOf(MediaStore.Images.ImageColumns.LATITUDE, MediaStore.Images.ImageColumns.LONGITUDE),
                    MediaStore.MediaColumns.DATA + " = ?",
                    arrayOf(fileTag.getFilePath()),
                    null
                )
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        val lat = cursor.getDouble(0)
                        val lng = cursor.getDouble(1)
                        if (lat != 0.0 && lng != 0.0) {
                            d("mediaStore latLng is available")
                            latLng = LatLng(lat, lng)
                        }
                    }
                    cursor.close()
                }
            }
            // Get latLng from exif for jpg if MediaStore failed
            if (latLng == null && fileTag.isJpg()) {
                val exifInterface = ExifInterface(fileTag.getFilePath())
                val latLong = exifInterface.latLong
                if (latLong != null) {
                    d("exif latLng is available")
                    latLng = LatLng(latLong[0], latLong[1])
                }
            }
            if (latLng != null) {
                val infoSet = InfoListAdapter.InfoSet(R.drawable.ic_place_white_24dp)
                val _latLng = latLng!!
                infoSet.textPrimary =
                        "${String.format("%.3f", _latLng.latitude)}, ${String.format("%.3f", _latLng.longitude)}"
                try {
                    val addresses = Geocoder(this@InfoActivity, Locale.getDefault()).getFromLocation(
                        _latLng.latitude,
                        _latLng.longitude,
                        1
                    )
                    val cityName = addresses[0].locality
                    val stateName = addresses[0].adminArea
                    // If geoCoder successful, then make coordinate secondary
                    infoSet.textSecondary =
                            "${String.format("%.3f", _latLng.latitude)}, ${String.format("%.3f", _latLng.longitude)}"
                    when {
                        !cityName.isNullOrEmpty() && !stateName.isNullOrEmpty() -> {
                            infoSet.textPrimary = "$cityName, $stateName"
                        }
                        !cityName.isNullOrEmpty() -> {
                            infoSet.textPrimary = cityName
                        }
                        !stateName.isNullOrEmpty() -> {
                            infoSet.textPrimary = stateName
                        }
                        else -> {
                            // City and state not found, show coordinate only as primary, cancel secondary
                            infoSet.textSecondary = ""
                        }
                    }
                } catch (ex: Exception) {
                    e(ex)
                } finally {
                    // If geoCoder failed, proceed to show coordinate
                    uiThread {
                        adapter.addInfo(infoSet, true)
                        setMap()
                    }
                }
            }
        }
    }

    // setMap
    private fun setMap() {
        // Check Google Play Service is available, see https://stackoverflow.com/a/32634091/3584439
        val googleApiAvailability = GoogleApiAvailability.getInstance()
        val result = googleApiAvailability.isGooglePlayServicesAvailable(this)
        if (result == ConnectionResult.SUCCESS) {
            mapContainer.isVisible = true
            val mapFragment = supportFragmentManager.findFragmentById(R.id.infoActivity_map) as? SupportMapFragment
            mapFragment?.getMapAsync(this)
        } else {
            // If not available, show error dialog if not prompt before
            if (googleApiAvailability.isUserResolvableError(result) && !requestedPlayServiceUpdate) {
                googleApiAvailability.showErrorDialogFragment(this, result, REQUEST_GOOGLE_PLAY_SERVICES)
            } else {
                snackProgressBarManager.shortSnackBar(this, R.string.toast_google_play_services_not_available_map)
            }
        }
    }

    // onMapReady
    override fun onMapReady(googleMap: GoogleMap?) {
        val latLng = this.latLng
        if (googleMap != null && latLng != null) {
            with(googleMap) {
                moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 13f))
                addMarker(MarkerOptions().position(latLng))
            }
        }
    }

    // onOptionsItemSelected
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                // To avoid destroying source activity
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // onActivityResult
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_GOOGLE_PLAY_SERVICES) {
            requestedPlayServiceUpdate = true
            setMap()
        }
    }

}