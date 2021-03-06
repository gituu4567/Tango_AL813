/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.incallui;

import com.android.contacts.common.CallUtil;
import com.mediatek.incallui.CallDetailChangeHandler;
import com.mediatek.incallui.InCallTrace;
import com.mediatek.incallui.volte.ConferenceChildrenChangeHandler;
import com.mediatek.incallui.volte.InCallUIVolteUtils;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.CallProperties;
import android.telecom.DisconnectCause;
import android.telecom.GatewayInfo;
import android.telecom.InCallService.VideoCall;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import android.telephony.SubscriptionManager;
import android.provider.Settings;

/**
 * Describes a single call and its state.
 */
public final class Call {
    /* Defines different states of this call */
    public static class State {
        public static final int INVALID = 0;
        public static final int NEW = 1;            /* The call is new. */
        public static final int IDLE = 2;           /* The call is idle.  Nothing active */
        public static final int ACTIVE = 3;         /* There is an active call */
        public static final int INCOMING = 4;       /* A normal incoming phone call */
        public static final int CALL_WAITING = 5;   /* Incoming call while another is active */
        public static final int DIALING = 6;        /* An outgoing call during dial phase */
        public static final int REDIALING = 7;      /* Subsequent dialing attempt after a failure */
        public static final int ONHOLD = 8;         /* An active phone call placed on hold */
        public static final int DISCONNECTING = 9;  /* A call is being ended. */
        public static final int DISCONNECTED = 10;  /* State after a call disconnects */
        public static final int CONFERENCED = 11;   /* Call part of a conference call */
        public static final int PRE_DIAL_WAIT = 12; /* Waiting for user before outgoing call */
        public static final int CONNECTING = 13;    /* Waiting for Telecomm broadcast to finish */


        public static boolean isConnectingOrConnected(int state) {
            switch(state) {
                case ACTIVE:
                case INCOMING:
                case CALL_WAITING:
                case CONNECTING:
                case DIALING:
                case REDIALING:
                case ONHOLD:
                case CONFERENCED:
                    return true;
                default:
            }
            return false;
        }

        public static boolean isDialing(int state) {
            return state == DIALING || state == REDIALING;
        }

        public static boolean isIncoming(int state) {
            return state == INCOMING || state == CALL_WAITING;
        }

        public static String toString(int state) {
            switch (state) {
                case INVALID:
                    return "INVALID";
                case NEW:
                    return "NEW";
                case IDLE:
                    return "IDLE";
                case ACTIVE:
                    return "ACTIVE";
                case INCOMING:
                    return "INCOMING";
                case CALL_WAITING:
                    return "CALL_WAITING";
                case DIALING:
                    return "DIALING";
                case REDIALING:
                    return "REDIALING";
                case ONHOLD:
                    return "ONHOLD";
                case DISCONNECTING:
                    return "DISCONNECTING";
                case DISCONNECTED:
                    return "DISCONNECTED";
                case CONFERENCED:
                    return "CONFERENCED";
                case PRE_DIAL_WAIT:
                    return "PRE_DIAL_WAIT";
                case CONNECTING:
                    return "CONNECTING";
                default:
                    return "UNKNOWN";
            }
        }
    }

    /**
     * Defines different states of session modify requests, which are used to upgrade to video, or
     * downgrade to audio.
     */
    public static class SessionModificationState {
        public static final int NO_REQUEST = 0;
        public static final int WAITING_FOR_RESPONSE = 1;
        public static final int REQUEST_FAILED = 2;
        public static final int RECEIVED_UPGRADE_TO_VIDEO_REQUEST = 3;
    }

    private static final String ID_PREFIX = Call.class.getSimpleName() + "_";
    private static int sIdCounter = 0;

    private android.telecom.Call.Listener mTelecommCallListener =
            new android.telecom.Call.Listener() {
                @Override
                public void onStateChanged(android.telecom.Call call, int newState) {
                    InCallTrace.begin("telecomStateChanged");
                    update();
                    InCallTrace.end("telecomStateChanged");
                }

                @Override
                public void onParentChanged(android.telecom.Call call,
                        android.telecom.Call newParent) {
                    update();
                }

                @Override
                public void onChildrenChanged(android.telecom.Call call,
                        List<android.telecom.Call> children) {
                    /// M: for VOLTE @{
                    handleChildrenChanged();
                    /// @}
                    update();
                }

                @Override
                public void onDetailsChanged(android.telecom.Call call,
                        android.telecom.Call.Details details) {
                    InCallTrace.begin("telecomDetailsChanged");
                    update();
                    InCallTrace.end("telecomDetailsChanged");
                    /// M: for VOLTE @{
                    handleDetailsChanged(details);
                    /// @}
                }

                @Override
                public void onCannedTextResponsesLoaded(android.telecom.Call call,
                        List<String> cannedTextResponses) {
                    update();
                }

                @Override
                public void onPostDialWait(android.telecom.Call call,
                        String remainingPostDialSequence) {
                    update();
                }

                @Override
                public void onVideoCallChanged(android.telecom.Call call,
                        VideoCall videoCall) {
                    update();
                }

                @Override
                public void onCallDestroyed(android.telecom.Call call) {
                    call.removeListener(mTelecommCallListener);
                }

                @Override
                public void onConferenceableCallsChanged(android.telecom.Call call,
                        List<android.telecom.Call> conferenceableCalls) {
                    update();
                }
            };

    private final android.telecom.Call mTelecommCall;
    private final String mId;
    private int mState = State.INVALID;
    private DisconnectCause mDisconnectCause;
    private int mSessionModificationState;
    private final List<String> mChildCallIds = new ArrayList<>();

    private InCallVideoCallListener mVideoCallListener;

    public Call(android.telecom.Call telecommCall) {
        mTelecommCall = telecommCall;
        mId = ID_PREFIX + Integer.toString(sIdCounter++);
        updateFromTelecommCall();
        mTelecommCall.addListener(mTelecommCallListener);
        /// M: for Volte @{
        // ALPS01792379. Init old details first.
        mOldDetails = mTelecommCall.getDetails();
        /// @}
    }

    public android.telecom.Call getTelecommCall() {
        return mTelecommCall;
    }

    private void update() {
        InCallTrace.begin("callUpdate");
        int oldState = getState();
        updateFromTelecommCall();
        if (oldState != getState() && getState() == Call.State.DISCONNECTED) {
            CallList.getInstance().onDisconnect(this);
        } else {
            CallList.getInstance().onUpdate(this);
        }
        InCallTrace.end("callUpdate");
    }

    private void updateFromTelecommCall() {
        Log.d(this, "updateFromTelecommCall: " + mTelecommCall);
        setState(translateState(mTelecommCall.getState()));
        setDisconnectCause(mTelecommCall.getDetails().getDisconnectCause());

        if (mTelecommCall.getVideoCall() != null) {
            if (mVideoCallListener == null) {
                mVideoCallListener = new InCallVideoCallListener(this);
            }
            mTelecommCall.getVideoCall().setVideoCallListener(mVideoCallListener);
        }

        mChildCallIds.clear();
        for (int i = 0; i < mTelecommCall.getChildren().size(); i++) {
            mChildCallIds.add(
                    CallList.getInstance().getCallByTelecommCall(
                            mTelecommCall.getChildren().get(i)).getId());
        }
    }

    private static int translateState(int state) {
        switch (state) {
            case android.telecom.Call.STATE_NEW:
                return Call.State.NEW;
            case android.telecom.Call.STATE_CONNECTING:
                return Call.State.CONNECTING;
            case android.telecom.Call.STATE_PRE_DIAL_WAIT:
                return Call.State.PRE_DIAL_WAIT;
            case android.telecom.Call.STATE_DIALING:
                return Call.State.DIALING;
            case android.telecom.Call.STATE_RINGING:
                return Call.State.INCOMING;
            case android.telecom.Call.STATE_ACTIVE:
                return Call.State.ACTIVE;
            case android.telecom.Call.STATE_HOLDING:
                return Call.State.ONHOLD;
            case android.telecom.Call.STATE_DISCONNECTED:
                return Call.State.DISCONNECTED;
            case android.telecom.Call.STATE_DISCONNECTING:
                return Call.State.DISCONNECTING;
            default:
                return Call.State.INVALID;
        }
    }

    public String getId() {
        return mId;
    }

    public String getNumber() {
        if (mTelecommCall.getDetails().getGatewayInfo() != null) {
            return mTelecommCall.getDetails().getGatewayInfo()
                    .getOriginalAddress().getSchemeSpecificPart();
        }
        return getHandle() == null ? null : getHandle().getSchemeSpecificPart();
    }

    public Uri getHandle() {
        return mTelecommCall.getDetails().getHandle();
    }

    public int getState() {
        if (mTelecommCall.getParent() != null) {
            return State.CONFERENCED;
        } else {
            return mState;
        }
    }

    public void setState(int state) {
        mState = state;
    }

    public int getNumberPresentation() {
        return getTelecommCall().getDetails().getHandlePresentation();
    }

    public int getCnapNamePresentation() {
        return getTelecommCall().getDetails().getCallerDisplayNamePresentation();
    }

    public String getCnapName() {
        return getTelecommCall().getDetails().getCallerDisplayName();
    }

    /** Returns call disconnect cause, defined by {@link DisconnectCause}. */
    public DisconnectCause getDisconnectCause() {
        if (mState == State.DISCONNECTED || mState == State.IDLE) {
            return mDisconnectCause;
        }

        return new DisconnectCause(DisconnectCause.UNKNOWN);
    }

    public void setDisconnectCause(DisconnectCause disconnectCause) {
        mDisconnectCause = disconnectCause;
    }

    /** Returns the possible text message responses. */
    public List<String> getCannedSmsResponses() {
        return mTelecommCall.getCannedTextResponses();
    }

    /** Checks if the call supports the given set of capabilities supplied as a bit mask. */
    public boolean can(int capabilities) {
        int supportedCapabilities = mTelecommCall.getDetails().getCallCapabilities();

        if ((capabilities & android.telecom.Call.Details.CAPABILITY_MERGE_CONFERENCE) != 0) {
            // We allow you to merge if the capabilities allow it or if it is a call with
            // conferenceable calls.
            if (mTelecommCall.getConferenceableCalls().isEmpty() &&
                ((android.telecom.Call.Details.CAPABILITY_MERGE_CONFERENCE
                        & supportedCapabilities) == 0)) {
                // Cannot merge calls if there are no calls to merge with.
                return false;
            }
            capabilities &= ~android.telecom.Call.Details.CAPABILITY_MERGE_CONFERENCE;
        }
        return (capabilities == (capabilities & mTelecommCall.getDetails().getCallCapabilities()));
    }

    private boolean hasProperty(int property) {
        return property == (property & mTelecommCall.getDetails().getCallProperties());
    }

    /** Gets the time when the call first became active. */
    public long getConnectTimeMillis() {
        return mTelecommCall.getDetails().getConnectTimeMillis();
    }

    public boolean isConferenceCall() {
        return hasProperty(CallProperties.CONFERENCE);
    }

    public GatewayInfo getGatewayInfo() {
        return mTelecommCall.getDetails().getGatewayInfo();
    }

    public PhoneAccountHandle getAccountHandle() {
        return mTelecommCall.getDetails().getAccountHandle();
    }

    /**
     * Get subId for this call.
     * @return subId
     */
    public int getSubId() {
        if (getAccountHandle() == null || !isTelephonyCall()) {
            return INVALID_SUB_ID;
        }

        String subId = getAccountHandle().getId();
        if (TextUtils.isDigitsOnly(subId)) {
			//add by zhangjinqiang for show sim card incallui--start
			int slotId = SubscriptionManager.getSlotId(Integer.parseInt(subId));
			 Settings.System.putInt(InCallPresenter.getInstance().getContext().getContentResolver(), "slot_id", slotId);
 			//add by zhangjinqiang for show sim card incallui--end
            return Integer.parseInt(subId);
        }
        return INVALID_SUB_ID;
    }

    public VideoCall getVideoCall() {
        return mTelecommCall.getVideoCall();
    }

    public List<String> getChildCallIds() {
        return mChildCallIds;
    }

    public String getParentId() {
        android.telecom.Call parentCall = mTelecommCall.getParent();
        if (parentCall != null) {
            return CallList.getInstance().getCallByTelecommCall(parentCall).getId();
        }
        return null;
    }

    public int getVideoState() {
        return mTelecommCall.getDetails().getVideoState();
    }

    public boolean isVideoCall(Context context) {
        return CallUtil.isVideoEnabled(context) &&
                VideoProfile.VideoState.isBidirectional(getVideoState());
    }

    public void setSessionModificationState(int state) {
        boolean hasChanged = mSessionModificationState != state;
        mSessionModificationState = state;

        if (hasChanged) {
            update();
        }
    }

    public static boolean areSame(Call call1, Call call2) {
        if (call1 == null && call2 == null) {
            return true;
        } else if (call1 == null || call2 == null) {
            return false;
        }

        // otherwise compare call Ids
        return call1.getId().equals(call2.getId());
    }

    public boolean isTelephonyCall() {
        Context context = InCallPresenter.getInstance().getContext();
        if (context == null) {
            return false;
        }

        TelecomManager telecomManager = (TelecomManager) context.
                getSystemService(Context.TELECOM_SERVICE);
        PhoneAccount phoneAccount = telecomManager.getPhoneAccount(getAccountHandle());
        if (phoneAccount == null) {
            return false;
        }
        return (phoneAccount.getCapabilities() & PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
                == PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION;
    }

    public int getSessionModificationState() {
        return mSessionModificationState;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "[%s, %s, %s, children:%s, parent:%s, conferenceable:%s, " +
                "videoState:%d]",
                mId,
                State.toString(getState()),
                android.telecom.Call.Details
                        .capabilitiesToString(mTelecommCall.getDetails().getCallCapabilities()),
                mChildCallIds,
                getParentId(),
                this.mTelecommCall.getConferenceableCalls(),
                mTelecommCall.getDetails().getVideoState());
    }

    /// M: For VoLTE @{
    private android.telecom.Call.Details mOldDetails;     // to record details before onDetailsChanged().

    /**
     * get details of the call. Wrapper for mTelecommCall.getDetails().
     * @return
     */
    public android.telecom.Call.Details getDetails() {
        if (mTelecommCall != null) {
            return mTelecommCall.getDetails();
        } else {
             Log.d(this, "getDetails()... mTelecommCall is null, need check! ");
            return null;
        }
    }

    /**
     * This function used to check whether certain info has been changed, if changed, handle them.
     * @param newDetails
     */
    private void handleDetailsChanged(android.telecom.Call.Details newDetails) {
        CallDetailChangeHandler.getInstance().onCallDetailChanged(this, mOldDetails, newDetails);
        mOldDetails = newDetails;
    }

    /**
     * check whether the call is marked as Ecc by NW.
     * @return
     */
    public boolean isVolteMarkedEcc() {
        boolean isVolteEmerencyCall = false;
        isVolteEmerencyCall = InCallUIVolteUtils.isVolteMarkedEcc(getDetails());
        return isVolteEmerencyCall;
    }

    /**
     * get pau field received from NW.
     * @return
     */
    public String getVoltePauField() {
        String voltePauField = "";
        voltePauField = InCallUIVolteUtils.getVoltePauField(getDetails());
        return voltePauField;
    }

    /**
     * handle children change, notify member add or leave, only for VoLTE conference call.
     * Note: call this function before update() in onChildrenChanged(),
     * for mChildCallIds used here will be changed in update()
     */
    private void handleChildrenChanged() {
        Log.d(this, "handleChildrenChanged()...");
        if (!InCallUIVolteUtils.isVolteSupport() ||
                !can(android.telecom.Call.Details.CAPABILITY_VOLTE)) {
            // below feature is only for VoLTE conference, so skip if not VoLTE conference.
            return;
        }
        List<String> newChildrenIds = new ArrayList<String>();
        for (int i = 0; i < mTelecommCall.getChildren().size(); i++) {
            newChildrenIds.add(
                    CallList.getInstance().getCallByTelecommCall(
                            mTelecommCall.getChildren().get(i)).getId());
        }
        ConferenceChildrenChangeHandler.getInstance().handleChildrenChanged(mChildCallIds, newChildrenIds);
    }

    /**
     * This function translates call state to status string for conference
     * caller.
     * @param context The Context object for the call.
     * @return call status to show
     */
    public String getCallStatusFromState(Context context) {
        Log.d(this, "getCallStatusFromState() mState: " + mState);
        String callStatus = "";
        switch (mState) {
            case State.ACTIVE:
                callStatus = context.getString(R.string.call_status_online);
                break;
            case State.ONHOLD:
                callStatus = context.getString(R.string.call_status_onhold);
                break;
            case State.DIALING:
            case State.REDIALING:
                callStatus = context.getString(R.string.call_status_dialing);
                break;
            case State.DISCONNECTING:
                callStatus = context.getString(R.string.call_status_disconnecting);
                break;
            case State.DISCONNECTED:
                callStatus = context.getString(R.string.call_status_disconnected);
                break;
            default:
                Log.w(this, "getCallStatusFromState() un-expected state: " + mState);
                break;
        }
        return callStatus;
    }

    public static final int INVALID_SUB_ID = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    /// @}
}
