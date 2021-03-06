package com.peterlaurence.trekme.ui.mapcreate.views

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.*
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.peterlaurence.mapview.MapView
import com.peterlaurence.mapview.MapViewConfiguration
import com.peterlaurence.mapview.api.addMarker
import com.peterlaurence.mapview.api.moveMarker
import com.peterlaurence.trekme.R
import com.peterlaurence.trekme.core.map.TileStreamProvider
import com.peterlaurence.trekme.core.mapsource.MapSource
import com.peterlaurence.trekme.core.mapsource.MapSourceBundle
import com.peterlaurence.trekme.core.projection.MercatorProjection
import com.peterlaurence.trekme.core.providers.bitmap.*
import com.peterlaurence.trekme.core.providers.layers.IgnLayers
import com.peterlaurence.trekme.service.event.DownloadServiceStatusEvent
import com.peterlaurence.trekme.ui.LocationProviderHolder
import com.peterlaurence.trekme.ui.dialogs.SelectDialog
import com.peterlaurence.trekme.ui.mapcreate.components.Area
import com.peterlaurence.trekme.ui.mapcreate.components.AreaLayer
import com.peterlaurence.trekme.ui.mapcreate.components.AreaListener
import com.peterlaurence.trekme.ui.mapcreate.events.MapSourceSettingsEvent
import com.peterlaurence.trekme.ui.mapcreate.views.components.PositionMarker
import com.peterlaurence.trekme.ui.mapcreate.views.events.LayerSelectEvent
import com.peterlaurence.trekme.viewmodel.common.Location
import com.peterlaurence.trekme.viewmodel.common.LocationProvider
import com.peterlaurence.trekme.viewmodel.common.LocationViewModel
import com.peterlaurence.trekme.viewmodel.common.tileviewcompat.toMapViewTileStreamProvider
import com.peterlaurence.trekme.viewmodel.mapcreate.GoogleMapWmtsViewModel
import kotlinx.coroutines.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import kotlin.coroutines.CoroutineContext

/**
 * Displays Google Maps - compatible tile matrix sets.
 * For example :
 *
 * [IGN WMTS](https://geoservices.ign.fr/documentation/geoservices/wmts.html). A `GetCapabilities`
 * request reveals that each level is square area. Here is an example for level 18 :
 * ```
 * <TileMatrix>
 *   <ows:Identifier>18</ows:Identifier>
 *   <ScaleDenominator>2132.7295838497840572</ScaleDenominator>
 *   <TopLeftCorner>
 *     -20037508.3427892476320267 20037508.3427892476320267
 *   </TopLeftCorner>
 *   <TileWidth>256</TileWidth>
 *   <TileHeight>256</TileHeight>
 *   <MatrixWidth>262144</MatrixWidth>
 *   <MatrixHeight>262144</MatrixHeight>
 * </TileMatrix>
 * ```
 * This level correspond to a 256 * 262144 = 67108864 px wide and height area.
 * The `TopLeftCorner` corner contains the WebMercator coordinates. The bottom right corner has
 * implicitly the opposite coordinates.
 * **Beware** that this "level 18" is actually the 19th level (matrix set starts at 0).
 *
 * The same settings can be seen at [USGS WMTS](https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/WMTS/1.0.0/WMTSCapabilities.xml)
 * for the "GoogleMapsCompatible" TileMatrixSet (and not the "default028mm" one).
 *
 * @author peterLaurence on 11/05/18
 */
class GoogleMapWmtsViewFragment : Fragment(), CoroutineScope {
    private var job = Job()
    private lateinit var mapSource: MapSource
    private lateinit var rootView: ConstraintLayout
    private lateinit var mapView: MapView
    private lateinit var areaLayer: AreaLayer
    private lateinit var locationProvider: LocationProvider
    private lateinit var positionMarker: PositionMarker
    private lateinit var wmtsWarning: TextView
    private lateinit var wmtsWarningLink: TextView
    private lateinit var navigateToIgnCredentialsBtn: Button
    private lateinit var fabSave: FloatingActionButton
    private val projection = MercatorProjection()

    private val viewModel: GoogleMapWmtsViewModel by activityViewModels()
    private val locationViewModel: LocationViewModel by viewModels()

    private lateinit var area: Area

    /* Size of level 18 */
    private val mapSize = 67108864
    private val highestLevel = 18

    private val tileSize = 256
    private val x0 = -20037508.3427892476320267
    private val y0 = -x0
    private val x1 = -x0
    private val y1 = x0

    companion object {
        private const val ARG_MAP_SOURCE = "mapSource"

        @JvmStatic
        fun newInstance(mapSource: MapSourceBundle): GoogleMapWmtsViewFragment {
            val fragment = GoogleMapWmtsViewFragment()
            val args = Bundle()
            args.putParcelable(ARG_MAP_SOURCE, mapSource)
            fragment.arguments = args
            return fragment
        }
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    override fun onAttach(context: Context) {
        super.onAttach(context)

        if (context is LocationProviderHolder) {
            locationProvider = context.locationProvider
        } else {
            throw RuntimeException("$context must implement LocationProviderHolder")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mapSource = arguments?.getParcelable<MapSourceBundle>(ARG_MAP_SOURCE)?.mapSource
            ?: MapSource.OPEN_STREET_MAP

        locationViewModel.setLocationProvider(locationProvider)
        locationViewModel.getLocationLiveData().observe(this, Observer<Location> {
            it?.let {
                onLocationReceived(it)
            }
        })

        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        rootView =
            inflater.inflate(R.layout.fragment_wmts_view, container, false) as ConstraintLayout

        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        wmtsWarning = view.findViewById(R.id.fragmentWmtWarning)

        fabSave = view.findViewById(R.id.fabSave)
        fabSave.setOnClickListener { validateArea() }

        wmtsWarningLink = view.findViewById(R.id.fragmentWmtWarningLink)
        wmtsWarningLink.movementMethod = LinkMovementMethod.getInstance()

        /**
         * If there is something wrong with IGN credentials, a special button helps to go directly
         * to the credentials editing fragment.
         */
        navigateToIgnCredentialsBtn = view.findViewById(R.id.fragmentWmtsNagivateToIgnCredentials)
        navigateToIgnCredentialsBtn.setOnClickListener {
            EventBus.getDefault().post(MapSourceSettingsEvent(MapSource.IGN))
        }

        configure()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        /* Hide the app title */
        val actionBar = (activity as AppCompatActivity).supportActionBar
        actionBar?.setDisplayShowTitleEnabled(false)

        /* Clear the existing action menu */
        menu.clear()

        /* Fill the new one */
        inflater.inflate(R.menu.menu_fragment_map_create, menu)

        /* Only show the layer menu for IGN France for instance */
        val layerMenu = menu.findItem(R.id.map_layer_menu_id)
        layerMenu.isVisible = when (mapSource) {
            MapSource.IGN -> true
            else -> false
        }

        super.onCreateOptionsMenu(menu, inflater)
    }

    @SuppressLint("RestrictedApi")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.map_area_widget_id -> {
                if (this::areaLayer.isInitialized) {
                    areaLayer.detach()
                }
                addAreaLayer()
                fabSave.visibility = View.VISIBLE
            }
            R.id.map_layer_menu_id -> {
                val event = LayerSelectEvent(arrayListOf())
                val title = getString(R.string.ign_select_layer_title)
                val values = IgnLayers.values().map { it.publicName }
                val layerPublicName = viewModel.getLayerPublicNameForSource(mapSource)
                val layerSelectDialog =
                    SelectDialog.newInstance(title, values, layerPublicName, event)
                layerSelectDialog.show(
                    activity!!.supportFragmentManager,
                    "SelectDialog-${event.javaClass.canonicalName}"
                )
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onStart() {
        super.onStart()
        job = Job()
        EventBus.getDefault().register(this)
    }

    override fun onResume() {
        super.onResume()

        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()

        stopLocationUpdates()
    }

    override fun onStop() {
        EventBus.getDefault().unregister(this)
        job.cancel()
        super.onStop()
    }

    private fun startLocationUpdates() {
        locationViewModel.startLocationUpdates()
    }

    private fun stopLocationUpdates() {
        locationViewModel.stopLocationUpdates()
    }

    /**
     * Confirm to the user that the download started.
     */
    @Subscribe
    fun onDownloadServiceStatus(e: DownloadServiceStatusEvent) {
        if (e.started) {
            view?.let {
                val snackBar = Snackbar.make(it, R.string.download_confirm, Snackbar.LENGTH_SHORT)
                snackBar.show()
            }
        }
    }

    @Subscribe
    fun onLayerDefined(e: LayerSelectEvent) {
        /* Update the layer preference */
        viewModel.setLayerPublicNameForSource(mapSource, e.getSelection())

        /* The re-create the mapview */
        removeMapView()
        configure()
    }

    private fun configure() {
        /* 1- Check current condition are OK */
        checkTileAccessibility()

        /* 2- Create and add the MapView */
        val streamProvider = viewModel.createTileStreamProvider(mapSource)
        if (streamProvider != null) {
            addMapView(streamProvider)
        } else {
            showWarningMessage()
        }

        /* 3- Scroll to the init position if there is one pre-configured */
        viewModel.getScaleAndScrollInitConfig(mapSource)?.also {
            mapView.scale = it.scale
            mapView.scrollTo(it.scrollX, it.scrollY)
        }
    }

    /**
     * Simple check whether we are able to download tiles or not.
     * If not, display a warning.
     */
    private fun CoroutineScope.checkTileAccessibility(): Job = launch {
        async(Dispatchers.IO) {
            val tileStreamProvider = viewModel.createTileStreamProvider(mapSource)
                ?: return@async false
            return@async when (mapSource) {
                MapSource.IGN -> {
                    try {
                        checkIgnProvider(tileStreamProvider)
                    } catch (e: Exception) {
                        false
                    }
                }
                MapSource.IGN_SPAIN -> checkIgnSpainProvider(tileStreamProvider)
                MapSource.USGS -> checkUSGSProvider(tileStreamProvider)
                MapSource.OPEN_STREET_MAP -> checkOSMProvider(tileStreamProvider)
                MapSource.SWISS_TOPO -> checkSwissTopoProvider(tileStreamProvider)
            }
        }.await().also {
            try {
                if (!it) {
                    showWarningMessage()
                } else {
                    hideWarningMessage()
                }
            } catch (e: IllegalStateException) {
                /* Since the result of this job can happen anytime during the lifecycle of this
                 * fragment, we should be resilient regarding this kind of error */
            }
        }
    }

    private fun showWarningMessage() {
        wmtsWarning.visibility = View.VISIBLE
        wmtsWarningLink.visibility = View.VISIBLE

        if (mapSource == MapSource.IGN) {
            navigateToIgnCredentialsBtn.visibility = View.VISIBLE
            wmtsWarning.text = getText(R.string.mapcreate_warning_ign)
        } else {
            wmtsWarning.text = getText(R.string.mapcreate_warning_others)
        }
    }

    private fun hideWarningMessage() {
        wmtsWarning.visibility = View.GONE
        navigateToIgnCredentialsBtn.visibility = View.GONE
        wmtsWarningLink.visibility = View.GONE
    }

    private fun addMapView(tileStreamProvider: TileStreamProvider) {
        val context = this.context ?: return
        val mapView = MapView(context)

        val config = MapViewConfiguration(
            19, mapSize, mapSize, tileSize,
            tileStreamProvider.toMapViewTileStreamProvider()
        ).setWorkerCount(16)

        /* Particular case of OSM Maps, limit concurrency while fetching tiles to avoid being banned */
        if (mapSource == MapSource.OPEN_STREET_MAP) {
            config.setWorkerCount(2)
        }

        mapView.configure(config)

        /* Map calibration */
        mapView.defineBounds(x0, y0, x1, y1)

        /* Position marker */
        positionMarker = PositionMarker(context)
        mapView.addMarker(positionMarker, 0.0, 0.0, -0.5f, -0.5f)

        /* Add the view */
        setMapView(mapView)
    }

    private fun setMapView(mapView: MapView) {
        this.mapView = mapView
        this.mapView.id = R.id.tileview_ign_id
        this.mapView.isSaveEnabled = true
        val params = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        rootView.addView(mapView, 0, params)
    }

    private fun removeMapView() {
        rootView.removeViewAt(0)
    }

    private fun addAreaLayer() {
        view?.post {
            areaLayer = AreaLayer(context!!, object : AreaListener {
                override fun areaChanged(area: Area) {
                    this@GoogleMapWmtsViewFragment.area = area
                }

                override fun hideArea() {
                }

            })
            areaLayer.attachTo(mapView)
        }
    }

    /**
     * Called when the user validates his area by clicking on the floating action button.
     */
    private fun validateArea() {
        if (this::area.isInitialized) {
            val fm = activity?.supportFragmentManager
            if (fm != null) {
                mapSource.let {
                    val wmtsLevelsDialog = if (it == MapSource.IGN) {
                        WmtsLevelsDialogIgn.newInstance(area, MapSourceBundle(it))
                    } else {
                        WmtsLevelsDialog.newInstance(area, MapSourceBundle(it))
                    }
                    wmtsLevelsDialog.show(fm, "fragment")
                }
            }
        }
    }

    private fun onLocationReceived(location: Location) {
        /* If there is no MapView, no need to go further */
        if (!::mapView.isInitialized) {
            return
        }

        /* A Projection is always defined in this case */
        launch {
            val projectedValues = withContext(Dispatchers.Default) {
                projection.doProjection(location.latitude, location.longitude)
            }
            if (projectedValues != null) {
                updatePosition(projectedValues[0], projectedValues[1])
            }
        }
    }

    /**
     * Update the position on the map.
     *
     * @param x the projected X coordinate
     * @param y the projected Y coordinate
     */
    private fun updatePosition(x: Double, y: Double) {
        if (::positionMarker.isInitialized) {
            mapView.moveMarker(positionMarker, x, y)
        }
    }
}
