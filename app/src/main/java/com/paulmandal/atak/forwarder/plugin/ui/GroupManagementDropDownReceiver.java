package com.paulmandal.atak.forwarder.plugin.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;
import com.paulmandal.atak.forwarder.R;
import com.paulmandal.atak.forwarder.comm.CotMessageCache;
import com.paulmandal.atak.forwarder.comm.commhardware.CommHardware;
import com.paulmandal.atak.forwarder.comm.queue.CommandQueue;
import com.paulmandal.atak.forwarder.group.GroupInfo;
import com.paulmandal.atak.forwarder.group.GroupTracker;
import com.paulmandal.atak.forwarder.group.UserInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import eo.view.batterymeter.BatteryMeterView;

public class GroupManagementDropDownReceiver extends DropDownReceiver implements DropDown.OnStateListener,
        GroupTracker.UpdateListener,
        CommandQueue.Listener,
        CommHardware.ConnectionStateListener,
        CommHardware.BatteryInfoListener {
    public static final String TAG = "ATAKDBG." + GroupManagementDropDownReceiver.class.getSimpleName();
    public static final String SHOW_PLUGIN = "com.paulmandal.atak.forwarder.SHOW_PLUGIN";

    private static final int BATTERY_CHECK_INTERVAL_MS = 600000;

    private final MapView mMapView;

    private final Context mPluginContext;
    private final Context mAtakContext;

    private GroupTracker mGroupTracker;
    private CommHardware mCommHardware;
    private CotMessageCache mCotMessageCache;
    private CommandQueue mCommandQueue;

    private final View mTemplateView;
    private EditMode mEditMode;

    private List<UserInfo> mUsers;
    private List<UserInfo> mModifiedUsers;

    private TextView mGroupIdTextView;
    private TextView mMessageQueueLengthTextView;
    private ListView mGroupMembersListView;
    private Button mCreateGroupButton;
    private Button mScanOrUnpair;
    private TextView mConnectionStatusTextView;

    private BatteryMeterView mBatteryMeterView;

    private boolean mIsDropDownOpen;

    private final Runnable mBatteryChargeCheckRunnable;
    private long mLastBatteryCheckTime = 0;

    public GroupManagementDropDownReceiver(final MapView mapView,
                                           final Context context,
                                           final Context atakContext,
                                           final GroupTracker groupTracker,
                                           final CommHardware commHardware,
                                           final CotMessageCache cotMessageCache,
                                           final CommandQueue commandQueue) {
        super(mapView);
        mMapView = mapView;
        mPluginContext = context;
        mAtakContext = atakContext;
        mGroupTracker = groupTracker;
        mCommHardware = commHardware;
        mCotMessageCache = cotMessageCache;
        mCommandQueue = commandQueue;

        // Remember to use the PluginLayoutInflator if you are actually inflating a custom view
        // In this case, using it is not necessary - but I am putting it here to remind
        // developers to look at this Inflator
        mTemplateView = PluginLayoutInflater.inflate(context, R.layout.main_layout, null);

        // Set up tabs
        TabHost tabs = (TabHost) mTemplateView.findViewById(R.id.tab_host);
        tabs.setup();
        TabHost.TabSpec spec = tabs.newTabSpec("tab_settings");
        spec.setContent(R.id.tab_settings);
        spec.setIndicator("Settings");
        tabs.addTab(spec);
        spec = tabs.newTabSpec("tab_advanced");
        spec.setContent(R.id.tab_advanced);
        spec.setIndicator("Advanced");
        tabs.addTab(spec);

        // Set up the rest of the UI

        mGroupIdTextView = (TextView) mTemplateView.findViewById(R.id.textview_group_id);
        mMessageQueueLengthTextView = (TextView)mTemplateView.findViewById(R.id.textview_message_queue_length);
        mCreateGroupButton = (Button) mTemplateView.findViewById(R.id.button_create_group);
        mGroupMembersListView = (ListView) mTemplateView.findViewById(R.id.listview_group_members);

        mConnectionStatusTextView = (TextView) mTemplateView.findViewById(R.id.textview_connection_status);

        Button clearData = (Button) mTemplateView.findViewById(R.id.button_clear_data);
        Button broadcastDiscovery = (Button) mTemplateView.findViewById(R.id.button_broadcast_discovery);
        Button clearMessageCache = (Button) mTemplateView.findViewById(R.id.button_clear_message_cache);
        Button clearMessageQueue = (Button) mTemplateView.findViewById(R.id.button_clear_message_queue);
        Button setDefaultCachePurgeTime = (Button) mTemplateView.findViewById(R.id.button_set_default_purge_time_ms);
        Button setPliCachePurgeTime = (Button) mTemplateView.findViewById(R.id.button_set_pli_purge_time_ms);
        mScanOrUnpair = (Button) mTemplateView.findViewById(R.id.button_scan_or_unpair);

        EditText cachePurgeTimeMins = (EditText) mTemplateView.findViewById(R.id.edittext_default_purge_time_mins);
        EditText pliPurgeTimeS = (EditText) mTemplateView.findViewById(R.id.edittext_pli_purge_time_s);

        mMessageQueueLengthTextView.setText(String.format(Locale.getDefault(), "%d", commandQueue.getQueueSize()));
        cachePurgeTimeMins.setText(String.format(Locale.getDefault(), "%d", mCotMessageCache.getDefaultCachePurgeTimeMs() / 60000));
        pliPurgeTimeS.setText(String.format(Locale.getDefault(), "%d", mCotMessageCache.getPliCachePurgeTimeMs() / 1000));

        mBatteryMeterView = (BatteryMeterView) mTemplateView.findViewById(R.id.battery_meter);
        mBatteryMeterView.setChargeLevel(commHardware.getBatteryChargePercentage());
        mBatteryMeterView.setCharging(commHardware.isBatteryCharging());

        broadcastDiscovery.setOnClickListener((View v) -> {
            Toast.makeText(mAtakContext, "Broadcasting discovery message", Toast.LENGTH_SHORT).show();
            commHardware.broadcastDiscoveryMessage();
        });

        clearData.setOnClickListener((View v) -> {
            Toast.makeText(mAtakContext, "Clearing all plugin data", Toast.LENGTH_LONG).show();
            mGroupTracker.clearData();
            mCotMessageCache.clearData();
            mCommandQueue.clearData();
            updateUi();
        });

        clearMessageCache.setOnClickListener((View v) -> {
            Toast.makeText(mAtakContext, "Clearing duplicate message cache", Toast.LENGTH_SHORT).show();
            mCotMessageCache.clearData();
        });

        clearMessageQueue.setOnClickListener((View v) -> {
            Toast.makeText(mAtakContext, "Clearing outgoing message queue", Toast.LENGTH_SHORT).show();
            mCommandQueue.clearData();
        });

        setDefaultCachePurgeTime.setOnClickListener((View v) -> {
            String cachePurgeTimeMinsStr = cachePurgeTimeMins.getText().toString();
            if (cachePurgeTimeMinsStr.equals("")) {
                return;
            }
            Toast.makeText(mAtakContext, "Set duplicate message cache TTL", Toast.LENGTH_SHORT).show();
            int cachePurgeTimeMs = Integer.parseInt(cachePurgeTimeMinsStr) * 60000;
            mCotMessageCache.setDefaultCachePurgeTimeMs(cachePurgeTimeMs);
        });

        setPliCachePurgeTime.setOnClickListener((View v) -> {
            String pliPurgeTimeSStr = pliPurgeTimeS.getText().toString();
            if (pliPurgeTimeSStr.equals("")) {
                return;
            }
            Toast.makeText(mAtakContext, "Set PLI message cache TTL", Toast.LENGTH_SHORT).show();
            int pliPurgeTimeSInt = Integer.parseInt(pliPurgeTimeSStr);
            mCotMessageCache.setPliCachePurgeTimeMs(pliPurgeTimeSInt * 1000);
        });

        mScanOrUnpair.setOnClickListener(mScanClickListener);

        mBatteryChargeCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (mCommHardware.isConnected()
                        && System.currentTimeMillis() - mLastBatteryCheckTime > BATTERY_CHECK_INTERVAL_MS) {
                    mCommHardware.requestBatteryStatus();
                    mLastBatteryCheckTime = System.currentTimeMillis();
                }
                mMapView.postDelayed(this, BATTERY_CHECK_INTERVAL_MS);
            }
        };

        mGroupTracker.setUpdateListener(this);
        commandQueue.setListener(this);
        commHardware.addConnectionStateListener(this);
        commHardware.setBatteryInfoListener(this);
    }

    public void disposeImpl() {
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action == null)
            return;

        if (action.equals(SHOW_PLUGIN)) {
            updateUi();

            List<Long> gIdsForGroup = new ArrayList<>();
            mCreateGroupButton.setOnClickListener((View v) -> {
                if (mEditMode == EditMode.ADD_USERS) {
                    List<Long> newGidsForGroup = new ArrayList<>();

                    StringBuilder usernamesForOutput = new StringBuilder();
                    boolean first = true;

                    for (int i = 0; i < mUsers.size(); i++) {
                        UserInfo originalUser = mUsers.get(i);
                        UserInfo modifiedUser = mModifiedUsers.get(i);
                        if (originalUser.isInGroup) {
                            gIdsForGroup.add(originalUser.gId);
                        }
                        if (!originalUser.isInGroup && modifiedUser.isInGroup) {
                            gIdsForGroup.add(originalUser.gId);
                            newGidsForGroup.add(originalUser.gId);

                            usernamesForOutput.append(first ? "" : ", ");
                            usernamesForOutput.append(originalUser.callsign);
                            first = false;
                        }
                    }

                    Toast.makeText(mAtakContext, "Adding users to group: " + usernamesForOutput, Toast.LENGTH_SHORT).show();
                    mCommHardware.addToGroup(gIdsForGroup, newGidsForGroup);
                } else {
                    StringBuilder usernamesForOutput = new StringBuilder();
                    boolean first = true;

                    for (UserInfo user : mModifiedUsers) {
                        if (user.isInGroup) {
                            gIdsForGroup.add(user.gId);

                            usernamesForOutput.append(first ? "" : ", ");
                            usernamesForOutput.append(user.callsign);
                            first = false;
                        }
                    }
                    Toast.makeText(mAtakContext, "Creating group with users: " + usernamesForOutput, Toast.LENGTH_SHORT).show();
                    mCommHardware.createGroup(gIdsForGroup);
                }
            });

            mMapView.post(mBatteryChargeCheckRunnable);

            showDropDown(mTemplateView, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, false, this);
        }
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean isVisible) {
        mIsDropDownOpen = true;
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
        mIsDropDownOpen = false;
        mMapView.removeCallbacks(mBatteryChargeCheckRunnable);
    }

    @Override
    public void onUsersUpdated() {
        Toast.makeText(mAtakContext, "User list updated", Toast.LENGTH_SHORT).show();
        updateUi();
    }

    @Override
    public void onGroupUpdated() {
        Toast.makeText(mAtakContext, "Group membership updated", Toast.LENGTH_SHORT).show();
        updateUi();
    }

    public boolean isDropDownOpen() {
        return mIsDropDownOpen;
    }

    private View.OnClickListener mScanClickListener = (View v) -> mCommHardware.connect();

    private View.OnClickListener mUnpairClickListener = (View v) -> mCommHardware.disconnect();

    private void updateUi() {
        setEditModeAndUiForGroup();
        setupListView();
    }

    private void setEditModeAndUiForGroup() {
        GroupInfo groupInfo = mGroupTracker.getGroup();
        if (groupInfo != null) {
            mGroupIdTextView.setText(String.format(Locale.getDefault(), "%d", groupInfo.groupId));
            mCreateGroupButton.setText(R.string.add_to_group);
            mEditMode = EditMode.ADD_USERS;
        } else {
            mEditMode = EditMode.NEW_GROUP;
        }
    }

    private void setupListView() {
        mUsers = mGroupTracker.getUsers();
        mModifiedUsers = new ArrayList<>(mUsers.size());
        for (UserInfo user : mUsers) {
            mModifiedUsers.add(user.clone());
        }

        GroupMemberDataAdapter groupMemberDataAdapter = new GroupMemberDataAdapter(mPluginContext, mModifiedUsers, mEditMode);
        mGroupMembersListView.setAdapter(groupMemberDataAdapter);
    }

    @Override
    public void onMessageQueueSizeChanged(int size) {
        mMessageQueueLengthTextView.setText(String.format(Locale.getDefault(), "%d", size));
    }

    @Override
    public void onConnectionStateChanged(CommHardware.ConnectionState connectionState) {
        switch (connectionState) {
            case SCANNING:
                handleScanStarted();
                break;
            case TIMEOUT:
                handleScanTimeout();
                break;
            case CONNECTED:
                handleDeviceConnected();
                break;
            case DISCONNECTED:
                handleDeviceDisconnected();
                break;
        }
    }

    public void handleScanStarted() {
        Toast.makeText(mAtakContext, "Scanning for comm device", Toast.LENGTH_SHORT).show();
        mConnectionStatusTextView.setText(R.string.connection_status_scanning);
        mScanOrUnpair.setOnClickListener(null);
    }

    public void handleScanTimeout() {
        Toast.makeText(mAtakContext, "Scanning for comm device timed out, ready device and then rescan in settings menu!", Toast.LENGTH_LONG).show();
        mConnectionStatusTextView.setText(R.string.connection_status_timeout);
        mScanOrUnpair.setOnClickListener(mScanClickListener);
        mScanOrUnpair.setText(R.string.scan);
    }

    public void handleDeviceConnected() {
        Toast.makeText(mAtakContext, "Comm device connected", Toast.LENGTH_SHORT).show();
        mConnectionStatusTextView.setText(R.string.connection_status_connected);
        mScanOrUnpair.setOnClickListener(mUnpairClickListener);
        mScanOrUnpair.setText(R.string.unpair);
    }

    public void handleDeviceDisconnected() {
        Toast.makeText(mAtakContext, "Comm device disconnected", Toast.LENGTH_SHORT).show();
        mConnectionStatusTextView.setText(R.string.connection_status_disconnected);
        mScanOrUnpair.setOnClickListener(mScanClickListener);
        mScanOrUnpair.setText(R.string.scan);
    }

    @Override
    public void onChargeStateChanged(boolean isCharging) {
        mBatteryMeterView.setCharging(isCharging);
    }

    @Override
    public void onGotBatteryInfo(int percentageCharged) {
        mBatteryMeterView.setChargeLevel(percentageCharged);
    }
}
