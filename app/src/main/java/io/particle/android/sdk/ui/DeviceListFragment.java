package io.particle.android.sdk.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.design.widget.Snackbar.Callback;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v4.util.Pair;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.getbase.floatingactionbutton.AddFloatingActionButton;
import com.getbase.floatingactionbutton.FloatingActionsMenu;
import com.tumblr.bookends.Bookends;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.annotation.ParametersAreNonnullByDefault;

import io.particle.android.sdk.DevicesLoader;
import io.particle.android.sdk.DevicesLoader.DevicesLoadResult;
import io.particle.android.sdk.cloud.ParticleCloudException;
import io.particle.android.sdk.cloud.ParticleDevice;
import io.particle.android.sdk.devicesetup.ParticleDeviceSetupLibrary;
import io.particle.android.sdk.devicesetup.ParticleDeviceSetupLibrary.DeviceSetupCompleteReceiver;
import io.particle.android.sdk.ui.Comparators.BooleanComparator;
import io.particle.android.sdk.ui.Comparators.ComparatorChain;
import io.particle.android.sdk.ui.Comparators.NullComparator;
import io.particle.android.sdk.utils.EZ;
import io.particle.android.sdk.utils.TLog;
import io.particle.android.sdk.utils.ui.Toaster;
import io.particle.android.sdk.utils.ui.Ui;
import io.particle.sdk.app.R;

import static io.particle.android.sdk.utils.Py.list;
import static io.particle.android.sdk.utils.Py.truthy;

import io.particle.android.sdk.utils.IabBroadcastReceiver;
import io.particle.android.sdk.utils.IabBroadcastReceiver.IabBroadcastListener;
import io.particle.android.sdk.utils.IabHelper;
import io.particle.android.sdk.utils.IabHelper.IabAsyncInProgressException;
import io.particle.android.sdk.utils.IabResult;
import io.particle.android.sdk.utils.Inventory;
import io.particle.android.sdk.utils.Purchase;


@ParametersAreNonnullByDefault
public class DeviceListFragment extends Fragment
        implements LoaderManager.LoaderCallbacks<DevicesLoadResult>, IabBroadcastListener {   // TODO: implements IabBroadcastListener

    ///////////////////
    // START BILLING //
    ///////////////////

    // Debug tag, for logging
    static final String TAG = "SimplySmart";

    // Does the user have the premium upgrade?
    boolean mIsSubscribed = false;

    // SKU for subscription
    static final String SKU_SUBSCRIPTION = "monthly";

    // (arbitrary) request code for the purchase flow
    static final int RC_REQUEST = 10001;

    // The helper object
    IabHelper mHelper;

    // Provides purchase notification while this app is running
    IabBroadcastReceiver mBroadcastReceiver;

    /////////////////
    // END BILLING //
    /////////////////

    public interface Callbacks {
        void onDeviceSelected(ParticleDevice device);
    }


    private static final TLog log = TLog.get(DeviceListFragment.class);

    // A no-op impl of {@link Callbacks}. Used when this fragment is not attached to an activity.
    private static final Callbacks dummyCallbacks = device -> { /* no-op */ };

    private SwipeRefreshLayout refreshLayout;
    private FloatingActionsMenu fabMenu;
    private DeviceListAdapter adapter;
    private Bookends<DeviceListAdapter> bookends;
    // FIXME: naming, document better
    private ProgressBar partialContentBar;
    private boolean isLoadingSnackbarVisible;

    private final ReloadStateDelegate reloadStateDelegate = new ReloadStateDelegate();
    private final Comparator<ParticleDevice> comparator = helpfulOrderDeviceComparator();

    private Callbacks callbacks = dummyCallbacks;
    private DeviceSetupCompleteReceiver deviceSetupCompleteReceiver;

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        callbacks = EZ.getCallbacksOrThrow(this, Callbacks.class);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View top = inflater.inflate(R.layout.fragment_device_list2, container, false);

        RecyclerView rv = Ui.findView(top, R.id.device_list);
        rv.setHasFixedSize(true);  // perf. optimization
        LinearLayoutManager layoutManager = new LinearLayoutManager(inflater.getContext());
        rv.setLayoutManager(layoutManager);

        @SuppressLint("InflateParams")
        View myHeader = inflater.inflate(R.layout.device_list_header, null);
        myHeader.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        partialContentBar = (ProgressBar) inflater.inflate(R.layout.device_list_footer, null);
        partialContentBar.setVisibility(View.INVISIBLE);
        partialContentBar.setLayoutParams(
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        adapter = new DeviceListAdapter(getActivity());
        // Add them as headers / footers
        bookends = new Bookends<>(adapter);
        bookends.addHeader(myHeader);
        bookends.addFooter(partialContentBar);

        rv.setAdapter(bookends);

        ItemClickSupport.addTo(rv).setOnItemClickListener((recyclerView, position, v) -> {
            // subtracting 1 from position because of header.  This is gross, but it's simple
            // and in this case adequate, so #SHIPIT.
            onDeviceRowClicked(recyclerView, position - 1, v);
        });

        return top;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        fabMenu = Ui.findView(view, R.id.add_device_fab);
        AddFloatingActionButton addPhoton = Ui.findView(view, R.id.action_set_up_a_photon);

        addPhoton.setOnClickListener(v -> {
            if (mIsSubscribed) {
                addPhotonDevice();
            }
            else {
                Toaster.s(getActivity(), "Please purchase subscription to add a device.");
                mHelper.flagEndAsync();
                purchaseSubscription();
            }
            fabMenu.collapse();
        });

        refreshLayout = Ui.findView(view, R.id.refresh_layout);
        refreshLayout.setOnRefreshListener(this::refreshDevices);

        deviceSetupCompleteReceiver = new ParticleDeviceSetupLibrary.DeviceSetupCompleteReceiver() {
            @Override
            public void onSetupSuccess(String id) {
                log.d("Successfully set up " + id);
            }

            @Override
            public void onSetupFailure() {
                log.w("Device not set up.");
            }
        };
        deviceSetupCompleteReceiver.register(getActivity());

        getLoaderManager().initLoader(R.id.device_list_devices_loader_id, null, this);
        refreshLayout.setRefreshing(true);

        ///////////////////
        // START BILLING //
        ///////////////////

        String base64EncodedPublicKey = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAgHxhfpotyc1w+zUTXoTfpFiTNKUrD4pTvFi6Xmf5VHOkfQdsK5FHprG1jhZ6DBUhKJdSdk+TrZJEiEvgICB0rlwHfbMhppIS5owC7/qg3mWI7sM6yraxpR7Qhidg8N5kH4XOQgboaY1xEN62snuB9BARYPE9rvR3IeVWRCeWkBn0tjNrEXBhITNZdCyQfqn/3dJ0L/Iw8WFyuc95gsxp5yOvzJZN6w7R8Gwh0n2E1YNZ0eAz7Np2KXgOinYrcDeX9a/WWxMTKN9Y8kFNN7jT8qHptdROLLpxE/3Up9YF2SKS52gCzbb7sc88iH5RnPY9XLX5FIDKlrukw+D7T2IKewIDAQAB";


        // Create the helper, passing it our context and the public key to verify signatures with
        Log.d(TAG, "Creating IAB helper.");
        mHelper = new IabHelper(getContext(), base64EncodedPublicKey);

        // enable debug logging (for a production application, you should set this to false).
        mHelper.enableDebugLogging(true);

        // Start setup. This is asynchronous and the specified listener
        // will be called once setup completes.
        Log.d(TAG, "Starting setup.");
        mHelper.startSetup(new IabHelper.OnIabSetupFinishedListener() {
            public void onIabSetupFinished(IabResult result) {
                Log.d(TAG, "Setup finished.");

                if (!result.isSuccess()) {
                    // Oh noes, there was a problem.
//                    complain("Problem setting up in-app billing: " + result);
                    Toaster.s(getContext(), "Problem setting up in-app billing: " + result);
                    return;
                }

                // Have we been disposed of in the meantime? If so, quit.
                if (mHelper == null) return;

                // Important: Dynamically register for broadcast messages about updated purchases.
                // We register the receiver here instead of as a <receiver> in the Manifest
                // because we always call getPurchases() at startup, so therefore we can ignore
                // any broadcasts sent while the app isn't running.
                // Note: registering this listener in an Activity is a bad idea, but is done here
                // because this is a SAMPLE. Regardless, the receiver must be registered after
                // IabHelper is setup, but before first call to getPurchases().
                mBroadcastReceiver = new IabBroadcastReceiver(DeviceListFragment.this);
                IntentFilter broadcastFilter = new IntentFilter(IabBroadcastReceiver.ACTION);
                getActivity().registerReceiver(mBroadcastReceiver, broadcastFilter);

                // IAB is fully set up. Now, let's get an inventory of stuff we own.
                Log.d(TAG, "Setup successful. Querying inventory.");
                try {
                    mHelper.queryInventoryAsync(mGotInventoryListener);
                } catch (IabAsyncInProgressException e) {
//                    complain("Error querying inventory. Another async operation in progress.");
                    Toaster.s(getContext(), "Error querying inventory. Another async operation in progress.");
                }
            }
        });

        /////////////////
        // END BILLING //
        /////////////////
    }

    /////////////////////////////
    // START BILLING FUNCTIONS //
    /////////////////////////////

    // Listener that's called when we finish querying the items and subscriptions we own
    IabHelper.QueryInventoryFinishedListener mGotInventoryListener = new IabHelper.QueryInventoryFinishedListener() {
        public void onQueryInventoryFinished(IabResult result, Inventory inventory) {
            Log.d(TAG, "Query inventory finished.");

            // Have we been disposed of in the meantime? If so, quit.
            if (mHelper == null) return;

            // Is it a failure?
            if (result.isFailure()) {
//                complain("Failed to query inventory: " + result);
                Toaster.s(getContext(), "Failed to query inventory: " + result);
                return;
            }

            Log.d(TAG, "Query inventory was successful.");

            /*
             * Check for items we own. Notice that for each purchase, we check
             * the developer payload to see if it's correct! See
             * verifyDeveloperPayload().
             */

            // Do we have the premium upgrade?
            Purchase subscriptionPurchase = inventory.getPurchase("monthly");
            mIsSubscribed = (subscriptionPurchase != null && verifyDeveloperPayload(subscriptionPurchase));
            Log.d(TAG, "User is " + (mIsSubscribed ? "SUBSCRIBED" : "NOT SUBSCRIBED"));

            if (!mIsSubscribed) {
                purchaseSubscription();
            }

            Log.d(TAG, "Initial inventory query finished; enabling main UI.");
        }
    };

    @Override
    public void receivedBroadcast() {
        // Received a broadcast notification that the inventory of items has changed
        Log.d(TAG, "Received broadcast notification. Querying inventory.");
        try {
            mHelper.queryInventoryAsync(mGotInventoryListener);
        } catch (IabAsyncInProgressException e) {
//            complain("Error querying inventory. Another async operation in progress.");
            Toaster.s(getContext(), "Error querying inventory. Another async operation in progress.");
        }
    }

    /** Verifies the developer payload of a purchase. */
    boolean verifyDeveloperPayload(Purchase p) {
        String payload = p.getDeveloperPayload();

        /*
         * TODO: verify that the developer payload of the purchase is correct. It will be
         * the same one that you sent when initiating the purchase.
         *
         * WARNING: Locally generating a random string when starting a purchase and
         * verifying it here might seem like a good approach, but this will fail in the
         * case where the user purchases an item on one device and then uses your app on
         * a different device, because on the other device you will not have access to the
         * random string you originally generated.
         *
         * So a good developer payload has these characteristics:
         *
         * 1. If two different users purchase an item, the payload is different between them,
         *    so that one user's purchase can't be replayed to another user.
         *
         * 2. The payload must be such that you can verify it even when the app wasn't the
         *    one who initiated the purchase flow (so that items purchased by the user on
         *    one device work on other devices owned by the user).
         *
         * Using your own server to store and verify developer payloads across app
         * installations is recommended.
         */

        return true;
    }

    // Callback for when a purchase is finished
    IabHelper.OnIabPurchaseFinishedListener mPurchaseFinishedListener = new IabHelper.OnIabPurchaseFinishedListener() {
        public void onIabPurchaseFinished(IabResult result, Purchase purchase) {
            Log.d(TAG, "Purchase finished: " + result + ", purchase: " + purchase);

            // if we were disposed of in the meantime, quit.
            if (mHelper == null) return;

            if (result.isFailure()) {
//                complain("Error purchasing: " + result);
                Toaster.s(getContext(), "Error purchasing: " + result);
//                setWaitScreen(false);
                return;
            }
            if (!verifyDeveloperPayload(purchase)) {
//                complain("Error purchasing. Authenticity verification failed.");
                Toaster.s(getContext(), "Error purchasing. Authenticity verification failed.");
//                setWaitScreen(false);
                return;
            }

            Log.d(TAG, "Purchase successful.");

            if (purchase.getSku().equals(SKU_SUBSCRIPTION)) {
                // bought the premium upgrade!
                Log.d(TAG, "Purchase is monthly subscription. Congratulating user.");
//                alert("Thank you for upgrading to premium!");
                Toaster.s(getContext(), "Thank you for subscribing!");
                mIsSubscribed = true;
//                updateUi();
//                setWaitScreen(false);
            }
        }
    };

    private void purchaseSubscription () {
        if (!mIsSubscribed) {
                        /* TODO: for security, generate your payload here for verification. See the comments on
         *        verifyDeveloperPayload() for more info. Since this is a SAMPLE, we just use
         *        an empty string, but on a production app you should carefully generate this. */
            String payload = "";

            try {
                mHelper.launchSubscriptionPurchaseFlow(getActivity(), SKU_SUBSCRIPTION, RC_REQUEST,
                        mPurchaseFinishedListener, payload);
            } catch (IabAsyncInProgressException e) {
                Toaster.s(getContext(), "Error launching purchase flow. Another async operation in progress.");
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, "onActivityResult(" + requestCode + "," + resultCode + "," + data);
        if (mHelper == null) return;

        // Pass on the activity result to the helper for handling
        if (!mHelper.handleActivityResult(requestCode, resultCode, data)) {
            // not handled, so handle it ourselves (here's where you'd
            // perform any handling of activity results not related to in-app
            // billing...
            super.onActivityResult(requestCode, resultCode, data);
        }
        else {
            Log.d(TAG, "onActivityResult handled by IABUtil.");
        }
    }

    ///////////////////////////
    // END BILLING FUNCTIONS //
    ///////////////////////////

    @Override
    public void onStart() {
        super.onStart();
        refreshDevices();
    }

    @Override
    public void onStop() {
        super.onStop();
        refreshLayout.setRefreshing(false);
        fabMenu.collapse();
        reloadStateDelegate.reset();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        callbacks = dummyCallbacks;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // very important:
        if (mBroadcastReceiver != null) {
            getActivity().unregisterReceiver(mBroadcastReceiver);
        }

        // very important:
        Log.d(TAG, "Destroying helper.");
        if (mHelper != null) {
            mHelper.disposeWhenFinished();
            mHelper = null;
        }
        deviceSetupCompleteReceiver.unregister(getActivity());
    }

    @Override
    public Loader<DevicesLoadResult> onCreateLoader(int i, Bundle bundle) {
        return new DevicesLoader(getActivity());
    }

    @Override
    public void onLoadFinished(Loader<DevicesLoadResult> loader, DevicesLoadResult result) {
        refreshLayout.setRefreshing(false);

        ArrayList<ParticleDevice> devices = new ArrayList<>(result.devices);
        Collections.sort(devices, comparator);

        reloadStateDelegate.onDeviceLoadFinished(loader, result);

        adapter.clear();
        adapter.addAll(devices);
        bookends.notifyDataSetChanged();
    }

    @Override
    public void onLoaderReset(Loader<DevicesLoadResult> loader) {
        // no-op
    }

    private void onDeviceRowClicked(RecyclerView recyclerView, int position, View view) {
        log.i("Clicked on item at position: #" + position);
        if (position >= bookends.getItemCount() || position == -1) {
            // we're at the header or footer view, do nothing.
            return;
        }

        // Notify the active callbacks interface (the activity, if the
        // fragment is attached to one) that an item has been selected.
        final ParticleDevice device = adapter.getItem(position);

        if (device.isFlashing()) {
            Toaster.s(getActivity(),
                    "Device is being flashed, please wait for the flashing process to end first");

        } else if (!device.isConnected()) {
            new AlertDialog.Builder(getActivity())
                    .setTitle("Device offline")
                    .setMessage(R.string.err_msg_device_is_offline)
                    .setPositiveButton(R.string.ok, (dialog, which) -> dialog.dismiss())
                    .show();

        } else if (!device.isRunningTinker()) { // TODO change this to device.isRunningSnapLight()
            if (mIsSubscribed) {
                DeviceActionsHelper.takeActionForDevice(R.id.action_device_toggle, getActivity(), device);
            }
            else {
                Toaster.s(getActivity(), "Please purchase subscription to toggle lights.");
                mHelper.flagEndAsync();
                purchaseSubscription();
            }

//            new AlertDialog.Builder(getActivity())
//                    .setTitle("Device not running Tinker")
//                    .setMessage("This device is not running Tinker firmware.")
//                    .setPositiveButton("Re-flash Tinker", (dialog, which) -> DeviceActionsHelper.takeActionForDevice(
//                            R.id.action_device_flash_tinker, getActivity(), device))
//                    .setNeutralButton("Tinker anyway", (dialog, which) -> callbacks.onDeviceSelected(device))
//                    .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
//                    .show();

        } else {
            callbacks.onDeviceSelected(device);
        }
    }

    public boolean onBackPressed() {
        if (fabMenu.isExpanded()) {
            fabMenu.collapse();
            return true;
        } else {
            return false;
        }
    }

    private void addPhotonDevice() {
        ParticleDeviceSetupLibrary.startDeviceSetup(getActivity());
    }

    private void addSparkCoreDevice() {
        String coreAppPkg = "io.spark.core.android";
        // Is the spark core app already installed?
        Intent intent = getActivity().getPackageManager().getLaunchIntentForPackage(coreAppPkg);
        if (intent == null) {
            // Nope.  Send the user to the store.
            intent = new Intent(Intent.ACTION_VIEW)
                    .setData(Uri.parse("market://details?id=" + coreAppPkg));
        }
        startActivity(intent);
    }

    private void addElectronDevice() {
        //        Intent intent = (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP)
//                ? new Intent(getActivity(), ElectronSetupActivity.class)
//                : new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.electron_setup_uri)));
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.electron_setup_uri)));
        startActivity(intent);
    }

    private void refreshDevices() {
        Loader<Object> loader = getLoaderManager().getLoader(R.id.device_list_devices_loader_id);
        loader.forceLoad();
    }


    static class DeviceListAdapter extends RecyclerView.Adapter<DeviceListAdapter.ViewHolder> {

        static class ViewHolder extends RecyclerView.ViewHolder {

            final View topLevel;
//            final TextView modelName;
            final ImageView productImage;
            final TextView deviceName;
            final TextView statusTextWithIcon;
//            final TextView productId;
            final ImageView overflowMenuIcon;

            public ViewHolder(View itemView) {
                super(itemView);
                topLevel = itemView;
//                modelName = Ui.findView(itemView, R.id.product_model_name);
                productImage = Ui.findView(itemView, R.id.product_image);
                deviceName = Ui.findView(itemView, R.id.product_name);
                statusTextWithIcon = Ui.findView(itemView, R.id.online_status);
//                productId = Ui.findView(itemView, R.id.product_id);
                overflowMenuIcon = Ui.findView(itemView, R.id.context_menu);
            }
        }


        private final List<ParticleDevice> devices = list();
        private final FragmentActivity activity;
        private Drawable defaultBackground;

        DeviceListAdapter(FragmentActivity activity) {
            this.activity = activity;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            // create a new view
            View v = LayoutInflater.from(parent.getContext()).inflate(
                    R.layout.row_device_list, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            final ParticleDevice device = devices.get(position);

            if (defaultBackground == null) {
                defaultBackground = holder.topLevel.getBackground();
            }

            if (position % 2 == 0) {
                holder.topLevel.setBackgroundResource(R.color.shaded_background);
            } else {
                if (VERSION.SDK_INT >= 16) {
                    holder.topLevel.setBackground(defaultBackground);
                } else {
                    holder.topLevel.setBackgroundDrawable(defaultBackground);
                }
            }

            switch (device.getDeviceType()) {
//                case CORE:
//                    holder.modelName.setText("Core");
//                    holder.productImage.setImageResource(R.drawable.core_vector);
//                    break;
//
//                case ELECTRON:
//                    holder.modelName.setText("Electron");
//                    holder.productImage.setImageResource(R.drawable.electron_vector_small);
//                    break;
//
//                default:
//                    holder.modelName.setText("Photon");
//                    holder.productImage.setImageResource(R.drawable.photon_vector_small);
//                    break;
            }

            Pair<String, Integer> statusTextAndColoredDot = getStatusTextAndColoredDot(device);
            holder.statusTextWithIcon.setText(statusTextAndColoredDot.first);
            holder.statusTextWithIcon.setCompoundDrawablesWithIntrinsicBounds(
                    0, 0, statusTextAndColoredDot.second, 0);

//            holder.productId.setText(device.getID().toUpperCase());

            Context ctx = holder.topLevel.getContext();
            String name = truthy(device.getName())
                    ? device.getName()
                    : ctx.getString(R.string.unnamed_device);
            holder.deviceName.setText(name);

            holder.overflowMenuIcon.setOnClickListener(
                    view -> showMenu(view, device)
            );
        }

        @Override
        public int getItemCount() {
            return devices.size();
        }

        void clear() {
            devices.clear();
            notifyDataSetChanged();
        }

        void addAll(List<ParticleDevice> toAdd) {
            devices.addAll(toAdd);
            notifyDataSetChanged();
        }

        ParticleDevice getItem(int position) {
            return devices.get(position);
        }

        private void showMenu(View v, final ParticleDevice device) {
            PopupMenu popup = new PopupMenu(v.getContext(), v);
            popup.inflate(R.menu.context_device_row);
            popup.setOnMenuItemClickListener(DeviceActionsHelper.buildPopupMenuHelper(activity, device));
            popup.show();
        }

        private Pair<String, Integer> getStatusTextAndColoredDot(ParticleDevice device) {
            int dot;
            String msg;
            if (device.isFlashing()) {
                dot = R.drawable.device_flashing_dot;
                msg = "Flashing";

            } else if (device.isConnected()) {
                if (device.isRunningTinker()) {
                    dot = R.drawable.online_dot;
                    msg = "Online";

                } else {
                    dot = R.drawable.online_dot;
                    msg = "Online";
                }

            } else {
                dot = R.drawable.offline_dot;
                msg = "Offline";
            }
            return Pair.create(msg, dot);
        }
    }


    private static Comparator<ParticleDevice> helpfulOrderDeviceComparator() {
        Comparator<ParticleDevice> deviceOnlineStatusComparator = (lhs, rhs) -> {
            return BooleanComparator.getTrueFirstComparator()
                    .compare(lhs.isConnected(), rhs.isConnected());
        };
        NullComparator<String> nullComparator = new NullComparator<>(false);
        Comparator<ParticleDevice> unnamedDevicesFirstComparator  = (lhs, rhs) -> {
            String lhname = lhs.getName();
            String rhname = rhs.getName();
            return nullComparator.compare(lhname, rhname);
        };

        ComparatorChain<ParticleDevice> chain;
        chain = new ComparatorChain<>(deviceOnlineStatusComparator, false);
        chain.addComparator(unnamedDevicesFirstComparator, false);
        return chain;
    }


    class ReloadStateDelegate {

        static final int MAX_RETRIES = 10;

        int retryCount = 0;

        void onDeviceLoadFinished(final Loader<DevicesLoadResult> loader, DevicesLoadResult result) {
            if (!result.isPartialResult) {
                reset();
                return;
            }

            retryCount++;
            if (retryCount > MAX_RETRIES) {
                // tried too many times, giving up. :(
                partialContentBar.setVisibility(View.INVISIBLE);
                return;
            }

            if (!isLoadingSnackbarVisible) {
                isLoadingSnackbarVisible = true;
                Snackbar.make(getView(), "Unable to load all devices", Snackbar.LENGTH_SHORT)
                        .setCallback(new Callback() {
                            @Override
                            public void onDismissed(Snackbar snackbar, int event) {
                                super.onDismissed(snackbar, event);
                                isLoadingSnackbarVisible = false;
                            }
                        }).show();
            }

            partialContentBar.setVisibility(View.VISIBLE);
            ((DevicesLoader) loader).setUseLongTimeoutsOnNextLoad(true);
            // FIXME: is it OK to call forceLoad() in loader callbacks?  Test and be certain.
            EZ.runOnMainThread(() -> {
                if (isResumed()) {
                    loader.forceLoad();
                }
            });
        }

        void reset() {
            retryCount = 0;
            partialContentBar.setVisibility(View.INVISIBLE);
        }

    }

}
