/*
 * Copyright (c) 2016 yvolk (Yuri Volkov), http://yurivolkov.com
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

package org.andstatus.app.timeline;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import org.andstatus.app.IntentExtra;
import org.andstatus.app.account.MyAccount;
import org.andstatus.app.context.MyContext;
import org.andstatus.app.context.MyContextHolder;
import org.andstatus.app.data.DbUtils;
import org.andstatus.app.data.ParsedUri;
import org.andstatus.app.database.CommandTable;
import org.andstatus.app.database.TimelineTable;
import org.andstatus.app.origin.Origin;
import org.andstatus.app.service.CommandResult;
import org.andstatus.app.util.BundleUtils;
import org.andstatus.app.util.ContentValuesUtils;
import org.andstatus.app.util.MyLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author yvolk@yurivolkov.com
 */
public class Timeline implements Comparable<Timeline> {
    private long id;
    private String name;
    private String description = "";

    private final TimelineType timelineType;
    /** The timeline combines messages from all accounts
     * or from Social Networks, e.g. Search in all Social Networks */
    private final boolean isCombined;
    /** "Authenticated User" used to retrieve/post to... this Timeline */
    private final MyAccount myAccount;
    /** A User as a parameter of this timeline.
     * This may be the same the Authenticated User ({@link #myAccount})
     * or some other User e.g. to get a list of messages by some other person/user of the Social Network*/
    private final long userId;
    /** Sometimes we don't know {@link #userId} yet... */
    private String userName = "";

    /** The Social Network of this timeline. Some timelines don't depend on
     * an Authenticated User ({@link #myAccount}), e.g. {@link TimelineType#PUBLIC} - this
     * timeline may be fetched by any authenticated user of this Social Network*/
    private final Origin origin;
    /** This may be used e.g. to search {@link TimelineType#PUBLIC} timeline */
    @NonNull
    private String searchQuery = "";

    private boolean synced = false;

    private boolean displayedInSelector = false;
    private long selectorOrder = 100;

    private long syncedDate = 0;
    private long syncFailedDate = 0;
    private String errorMessage = "";

    private long syncedTimesCount = 0;
    private long syncFailedTimesCount = 0;
    private long newItemsCount = 0;
    private long countSince = 0;

    private long syncedTimesCountTotal = 0;
    private long syncFailedTimesCountTotal = 0;
    private long newItemsCountTotal = 0;

    private String youngestPosition = "";
    private long youngestItemDate = 0;
    private long youngestSyncedDate = 0;

    private String oldestPosition = "";
    private long oldestItemDate = 0;
    private long oldestSyncedDate = 0;

    private long visibleItemId = 0;
    private int visibleY = 0;
    private long visibleYoungestDate = 0;

    private boolean changed = false;

    public Timeline(TimelineType timelineType, MyAccount myAccount, long userId, Origin origin) {
        this(MyContextHolder.get(), timelineType, myAccount, userId, origin);
    }

    public Timeline(MyContext myContext, TimelineType timelineType, MyAccount myAccount, long userId, Origin origin) {
        this.myAccount = fixedMyAccount(myContext, timelineType, myAccount, userId);
        this.userId = fixedUserId(timelineType, userId);
        this.origin = fixedOrigin(myContext, timelineType, myAccount, userId, origin);
        this.timelineType = fixedTimelineType(timelineType);
        this.isCombined = this.timelineType.isAtOrigin() ? !this.origin.isValid() : !this.myAccount.isValid();
        fixUserName(myContext);
    }

    private MyAccount fixedMyAccount(MyContext myContext, TimelineType timelineType, MyAccount myAccountIn, long userIdIn) {
        if (timelineType == null) {
            return MyAccount.getEmpty();
        }
        MyAccount myAccount = myAccountIn == null ?  MyAccount.getEmpty() : myAccountIn;
        long userId = timelineType.requiresUserToBeDefined() ? userIdIn : 0;
        if (myContext.persistentAccounts().isAccountUserId(userId)) {
            myAccount = myContext.persistentAccounts().fromUserId(userId);
        }
        if (timelineType.isAtOrigin() &&
                !timelineType.requiresUserToBeDefined() &&
                userId== 0 || (userId != 0 && !myContext.persistentAccounts().isAccountUserId(userId))
                ) {
            return MyAccount.getEmpty();
        }
        return myAccount;
    }

    private long fixedUserId(TimelineType timelineType, long userId) {
        if (userId == 0 && myAccount.isValid() && timelineType.requiresUserToBeDefined()) {
            return myAccount.getUserId();
        }
        return userId;
    }

    private Origin fixedOrigin(MyContext myContext, TimelineType timelineType, MyAccount myAccountIn, long userId, Origin origin) {
        Origin fixedOrigin = origin == null ? Origin.getEmpty() : origin;
        MyAccount ma = myContext.persistentAccounts().fromUserId(userId);
        if (!ma.isValid() && myAccountIn != null) {
            ma = myAccountIn;
        }
        if (ma.isValid()) {
            if (fixedOrigin.isValid() && fixedOrigin != ma.getOrigin()) {
                fixedOrigin = Origin.getEmpty();
            }
            if (timelineType.isAtOrigin() || !fixedOrigin.isValid()) {
                fixedOrigin = ma.getOrigin();
            }
        }
        return fixedOrigin;
    }

    private TimelineType fixedTimelineType(TimelineType timelineType) {
        if (timelineType == null) {
            return TimelineType.UNKNOWN;
        }
        if (timelineType.isAtOrigin()) {
            if (!origin.isValid()) {
                return TimelineType.UNKNOWN;
            }
        } else {
            if (!myAccount.isValid()) {
                return TimelineType.UNKNOWN;
            }
        }
        if (timelineType.requiresUserToBeDefined()) {
            if (userId == 0) {
                return TimelineType.UNKNOWN;
            }
            if (myAccount.getUserId() == userId) {
                switch (timelineType) {
                    case USER:
                        return TimelineType.SENT;
                    case FRIENDS:
                        return TimelineType.MY_FRIENDS;
                    case FOLLOWERS:
                        return TimelineType.MY_FOLLOWERS;
                    default:
                        break;
                }
            } else {
                switch (timelineType) {
                    case SENT:
                        return TimelineType.USER;
                    case MY_FRIENDS:
                        return TimelineType.FRIENDS;
                    case MY_FOLLOWERS:
                        return TimelineType.FOLLOWERS;
                    default:
                        break;
                }
            }
        }
        return timelineType;
    }

    private void fixUserName(MyContext myContext) {
        if (this.userId != 0) {
            MyAccount myAccount2 = myContext.persistentAccounts().fromUserId(this.userId);
            if (myAccount2.isValid()) {
                userName = myAccount2.getUsername();
            }
        }
    }

    @Override
    public int compareTo(Timeline another) {
        return selectorOrder == another.selectorOrder ? 0 :
                (selectorOrder >= another.selectorOrder ? 1 : -1 );
    }

    public void toContentValues(ContentValues values) {
        ContentValuesUtils.putNotZero(values, TimelineTable._ID, id);
        values.put(TimelineTable.TIMELINE_NAME, name);
        values.put(TimelineTable.TIMELINE_DESCRIPTION, description);
        values.put(TimelineTable.TIMELINE_TYPE, timelineType.save());
        values.put(TimelineTable.ACCOUNT_ID, myAccount.getUserId());
        values.put(TimelineTable.USER_ID, userId);
        values.put(TimelineTable.ORIGIN_ID, origin.getId());
        values.put(TimelineTable.SEARCH_QUERY, searchQuery);

        values.put(TimelineTable.SYNCED, synced);
        values.put(TimelineTable.DISPLAY_IN_SELECTOR, displayedInSelector);
        values.put(TimelineTable.SELECTOR_ORDER, selectorOrder);

        values.put(TimelineTable.SYNCED_DATE, syncedDate);
        values.put(TimelineTable.SYNC_FAILED_DATE, syncFailedDate);
        values.put(TimelineTable.ERROR_MESSAGE, errorMessage);

        values.put(TimelineTable.SYNCED_TIMES_COUNT, syncedTimesCount);
        values.put(TimelineTable.SYNC_FAILED_TIMES_COUNT, syncFailedTimesCount);
        values.put(TimelineTable.NEW_ITEMS_COUNT, newItemsCount);
        values.put(TimelineTable.COUNT_SINCE, countSince);
        values.put(TimelineTable.SYNCED_TIMES_COUNT_TOTAL, syncedTimesCountTotal);
        values.put(TimelineTable.SYNC_FAILED_TIMES_COUNT_TOTAL, syncFailedTimesCountTotal);
        values.put(TimelineTable.NEW_ITEMS_COUNT_TOTAL, newItemsCountTotal);

        values.put(TimelineTable.YOUNGEST_POSITION, youngestPosition);
        values.put(TimelineTable.YOUNGEST_ITEM_DATE, youngestItemDate);
        values.put(TimelineTable.YOUNGEST_SYNCED_DATE, youngestSyncedDate);
        values.put(TimelineTable.OLDEST_POSITION, oldestPosition);
        values.put(TimelineTable.OLDEST_ITEM_DATE, oldestItemDate);
        values.put(TimelineTable.OLDEST_SYNCED_DATE, oldestSyncedDate);

        values.put(TimelineTable.VISIBLE_ITEM_ID, visibleItemId);
        values.put(TimelineTable.VISIBLE_Y, visibleY);
        values.put(TimelineTable.VISIBLE_YOUNGEST_DATE, visibleYoungestDate);
    }

    public void toCommandContentValues(ContentValues values) {
        values.put(CommandTable.TIMELINE_ID, id);
        values.put(CommandTable.TIMELINE_TYPE, timelineType.save());
        values.put(CommandTable.ACCOUNT_ID, myAccount.getUserId());
        values.put(CommandTable.USER_ID, userId);
        values.put(CommandTable.USERNAME, userName);
        values.put(CommandTable.ORIGIN_ID, origin.getId());
        values.put(CommandTable.SEARCH_QUERY, searchQuery);
    }

    public Timeline fromSearch(boolean globalSearch) {
        Timeline timeline = this;
        if (globalSearch) {
            timeline = new Timeline(TimelineType.EVERYTHING,
                    this.getMyAccount(), this.getUserId(), this.getOrigin());
            timeline.setSearchQuery(this.getSearchQuery());
        }
        return timeline;
    }

    public static Timeline fromCursor(MyContext myContext, Cursor cursor) {
        Timeline timeline = new Timeline(
                TimelineType.load(DbUtils.getString(cursor, TimelineTable.TIMELINE_TYPE)),
                myContext.persistentAccounts()
                        .fromUserId(DbUtils.getLong(cursor, TimelineTable.ACCOUNT_ID)),
                DbUtils.getLong(cursor, TimelineTable.USER_ID),
                myContext.persistentOrigins()
                        .fromId(DbUtils.getLong(cursor, TimelineTable.ORIGIN_ID)));

        timeline.id = DbUtils.getLong(cursor, TimelineTable._ID);
        timeline.name = DbUtils.getString(cursor, TimelineTable.TIMELINE_NAME);
        timeline.description = DbUtils.getString(cursor, TimelineTable.TIMELINE_DESCRIPTION);
        timeline.searchQuery = DbUtils.getString(cursor, TimelineTable.SEARCH_QUERY);
        timeline.setSynced(DbUtils.getBoolean(cursor, TimelineTable.SYNCED));
        timeline.displayedInSelector = DbUtils.getBoolean(cursor, TimelineTable.DISPLAY_IN_SELECTOR);
        timeline.selectorOrder = DbUtils.getLong(cursor, TimelineTable.SELECTOR_ORDER);

        timeline.syncedDate = DbUtils.getLong(cursor, TimelineTable.SYNCED_DATE);
        timeline.syncFailedDate = DbUtils.getLong(cursor, TimelineTable.SYNC_FAILED_DATE);
        timeline.errorMessage = DbUtils.getString(cursor, TimelineTable.ERROR_MESSAGE);

        timeline.syncedTimesCount = DbUtils.getLong(cursor, TimelineTable.SYNCED_TIMES_COUNT);
        timeline.syncFailedTimesCount = DbUtils.getLong(cursor, TimelineTable.SYNC_FAILED_TIMES_COUNT);
        timeline.newItemsCount = DbUtils.getLong(cursor, TimelineTable.NEW_ITEMS_COUNT);
        timeline.countSince = DbUtils.getLong(cursor, TimelineTable.COUNT_SINCE);
        timeline.syncedTimesCountTotal = DbUtils.getLong(cursor, TimelineTable.SYNCED_TIMES_COUNT_TOTAL);
        timeline.syncFailedTimesCountTotal = DbUtils.getLong(cursor, TimelineTable.SYNC_FAILED_TIMES_COUNT_TOTAL);
        timeline.newItemsCountTotal = DbUtils.getLong(cursor, TimelineTable.NEW_ITEMS_COUNT_TOTAL);

        timeline.youngestPosition = DbUtils.getString(cursor, TimelineTable.YOUNGEST_POSITION);
        timeline.youngestItemDate = DbUtils.getLong(cursor, TimelineTable.YOUNGEST_ITEM_DATE);
        timeline.youngestSyncedDate = DbUtils.getLong(cursor, TimelineTable.YOUNGEST_SYNCED_DATE);
        timeline.oldestPosition = DbUtils.getString(cursor, TimelineTable.OLDEST_POSITION);
        timeline.oldestItemDate = DbUtils.getLong(cursor, TimelineTable.OLDEST_ITEM_DATE);
        timeline.oldestSyncedDate = DbUtils.getLong(cursor, TimelineTable.OLDEST_SYNCED_DATE);

        timeline.visibleItemId = DbUtils.getLong(cursor, TimelineTable.VISIBLE_ITEM_ID);
        timeline.visibleY = DbUtils.getInt(cursor, TimelineTable.VISIBLE_Y);
        timeline.visibleYoungestDate = DbUtils.getLong(cursor, TimelineTable.VISIBLE_YOUNGEST_DATE);

        return timeline;
    }


    public static Timeline fromCommandCursor(MyContext myContext, Cursor cursor) {
        Timeline timeline = new Timeline(
                TimelineType.load(DbUtils.getString(cursor, CommandTable.TIMELINE_TYPE)),
                myContext.persistentAccounts().fromUserId(DbUtils.getLong(cursor, CommandTable.ACCOUNT_ID)),
                DbUtils.getLong(cursor, CommandTable.USER_ID),
                myContext.persistentOrigins().fromId(DbUtils.getLong(cursor, CommandTable.ORIGIN_ID)));
        timeline.id = DbUtils.getLong(cursor, CommandTable.TIMELINE_ID);
        timeline.setUserName(DbUtils.getString(cursor, CommandTable.USERNAME));
        timeline.searchQuery = DbUtils.getString(cursor, CommandTable.SEARCH_QUERY);
        return timeline;
    }

    public Timeline fromIsCombined(boolean isCombined) {
        if (isCombined == this.isCombined) {
            return this;
        }
        MyAccount myAccount;
        Origin origin;
        if (isCombined) {
            origin = Origin.getEmpty();
            myAccount = MyAccount.getEmpty();
        } else {
            myAccount = MyContextHolder.get().persistentAccounts().getCurrentAccount();
            origin = MyContextHolder.get().persistentAccounts().getCurrentAccount().getOrigin();
        }
        Timeline timeline = new Timeline(getTimelineType(), myAccount, userId, origin);
        timeline.setUserName(userName);
        timeline.searchQuery = searchQuery;
        return timeline;
    }

    public Timeline fromMyAccount(MyAccount myAccount) {
        if ( isCombined ||
                ((myAccount == null || !myAccount.isValid()) && !this.myAccount.isValid()) ||
                (myAccount != null && myAccount.equals(this.myAccount)) ) {
            return this;
        }
        Timeline timeline = new Timeline(getTimelineType(), myAccount, userId, origin);
        timeline.setUserName(userName);
        timeline.setSearchQuery(searchQuery);
        return timeline;
    }

    public static Timeline getEmpty(MyAccount myAccount) {
        return new Timeline(TimelineType.UNKNOWN, myAccount, 0, null);
    }

    public boolean isEmpty() {
        return timelineType == TimelineType.UNKNOWN;
    }

    public boolean isValid() {
        return timelineType != TimelineType.UNKNOWN && id != 0;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        if (TextUtils.isEmpty(name)) {
            return timelineType.getTitle(MyContextHolder.get().context()).toString();
        }
        return name;
    }

    public String getDescription() {
        return description;
    }

    public TimelineType getTimelineType() {
        return timelineType;
    }

    public boolean isCombined() {
        return isCombined;
    }

    public Origin getOrigin() {
        return origin;
    }

    public MyAccount getMyAccount() {
        return myAccount;
    }

    public boolean isDisplayedInSelector() {
        return displayedInSelector;
    }

    public long getSelectorOrder() {
        return selectorOrder;
    }

    public static List<Timeline> addDefaultForAccount(MyAccount myAccount) {
        List<Timeline> timelines = new ArrayList<>();
        for (TimelineType timelineType : TimelineType.defaultMyAccountTimelineTypes) {
            saveNewDefaultTimeline(new Timeline(timelineType, myAccount, 0, null));
        }
        return timelines;
    }

    public static Collection<Timeline> addDefaultForOrigin(Origin origin) {
        List<Timeline> timelines = new ArrayList<>();
        for (TimelineType timelineType : TimelineType.defaultOriginTimelineTypes) {
            saveNewDefaultTimeline(new Timeline(timelineType, null, 0, origin));
        }
        return timelines;
    }

    protected static void saveNewDefaultTimeline(Timeline timeline) {
        timeline.displayedInSelector = true;
        timeline.selectorOrder = timeline.getTimelineType().ordinal();
        timeline.setSynced(timeline.getTimelineType().isSyncableByDefault());
        timeline.save();
    }

    public long saveIfChanged() {
        if (id != 0 && !changed) {
            return id;
        } else {
            return save();
        }
    }

    public long save() {
        ContentValues contentValues = new ContentValues();
        toContentValues(contentValues);
        if (getId() == 0) {
            id = DbUtils.addRowWithRetry(TimelineTable.TABLE_NAME, contentValues, 3);
        } else {
            DbUtils.updateRowWithRetry(TimelineTable.TABLE_NAME, getId(), contentValues, 3);
        }
        changed = false;
        return getId();
    }

    public void delete() {
        SQLiteDatabase db = MyContextHolder.get().getDatabase();
        if (db == null) {
            MyLog.d(this, "delete; Database is unavailable");
        } else {
            String sql = "DELETE FROM " + TimelineTable.TABLE_NAME + " WHERE _ID=" + getId();
            db.execSQL(sql);
            MyLog.v(this, "Timeline deleted: " + this);
        }
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();

         builder.append("Timeline{");
        if (myAccount.isValid()) {
            builder.append(myAccount.getAccountName());
            if (!myAccount.getOrigin().equals(origin) && origin.isValid()) {
                builder.append(", origin:" + origin.getName());
            }
        } else {
            builder.append(origin.isValid() ? origin.getName() : "(all origins)");
        }
        if (!TextUtils.isEmpty(name)) {
            builder.append(", name:'" + name + "'");
        }
        if (timelineType != TimelineType.UNKNOWN) {
            builder.append(", type:" + timelineType.save());
        }
        if (!TextUtils.isEmpty(userName)) {
            builder.append(", userName:'" + userName + "'");
        }
        if (userId != 0) {
            builder.append(", userId:" + userId);
        }
        if (hasSearchQuery()) {
            builder.append(" search:'" + getSearchQuery() + "'");
        }
        builder.append('}');
        return builder.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Timeline)) return false;

        Timeline timeline = (Timeline) o;

        if (userId != timeline.userId) return false;
        if (timelineType != timeline.timelineType) return false;
        if (!origin.equals(timeline.origin)) return false;
        if (!myAccount.getAccountName().equals(timeline.myAccount.getAccountName())) return false;
        return searchQuery.equals(timeline.searchQuery);
    }

    @Override
    public int hashCode() {
        int result = timelineType.hashCode();
        result = 31 * result + origin.hashCode();
        result = 31 * result + myAccount.getAccountName().hashCode();
        result = 31 * result + (int) (userId ^ (userId >>> 32));
        result = 31 * result + searchQuery.hashCode();
        return result;
    }

    public long getUserId() {
        return userId;
    }

    public static Timeline fromBundle(Bundle bundle) {
        MyAccount myAccount = MyAccount.fromBundle(bundle);
        Timeline timeline = getEmpty(myAccount);
        if (bundle != null) {
            timeline = MyContextHolder.get().persistentTimelines().fromId(
                    bundle.getLong(IntentExtra.TIMELINE_ID.key));
            if (timeline.isEmpty()) {
                Timeline timeline2 = new Timeline(
                        TimelineType.load(bundle.getString(IntentExtra.TIMELINE_TYPE.key)),
                        myAccount,
                        bundle.getLong(IntentExtra.USER_ID.key),
                        MyContextHolder.get().persistentOrigins().fromId(BundleUtils.fromBundle(bundle, IntentExtra.ORIGIN_ID)));
                timeline2.setUserName(BundleUtils.getString(bundle, IntentExtra.USER_NAME));
                timeline2.setSearchQuery(BundleUtils.getString(bundle, IntentExtra.SEARCH_QUERY));
                timeline = MyContextHolder.get().persistentTimelines().fromNewTimeLine(timeline2);
            }
        }
        return timeline;
    }

    // TODO: by ID only?!
    public static Timeline fromParsedUri(MyContext myContext, ParsedUri parsedUri, String searchQuery) {
        Timeline timeline = new Timeline(
                parsedUri.getTimelineType(),
                myContext.persistentAccounts().fromUserId(parsedUri.getAccountUserId()),
                parsedUri.getUserId(),
                myContext.persistentOrigins().fromId(parsedUri.getOriginId()));
        if (timeline.getTimelineType() == TimelineType.UNKNOWN) {
            MyLog.e(Timeline.class,"fromParsedUri; uri:" + parsedUri.getUri() + "; " + timeline );
        }
        timeline.setSearchQuery(parsedUri.getSearchQuery());
        if (TextUtils.isEmpty(timeline.searchQuery) && !TextUtils.isEmpty(searchQuery)) {
            timeline.setSearchQuery(searchQuery);
        }
        return timeline;
    }

    public boolean hasSearchQuery() {
        return !TextUtils.isEmpty(getSearchQuery());
    }

    @NonNull
    public String getSearchQuery() {
        return searchQuery;
    }

    public Timeline setSearchQuery(String searchQuery) {
        if (!TextUtils.isEmpty(searchQuery)) {
            this.searchQuery = searchQuery;
            changed = true;
        }
        return this;
    }

    public void toBundle(Bundle bundle) {
        BundleUtils.putNotZero(bundle, IntentExtra.TIMELINE_ID, id);
        if (myAccount.isValid()) {
            bundle.putString(IntentExtra.ACCOUNT_NAME.key, myAccount.getAccountName());
        }
        if (timelineType != TimelineType.UNKNOWN) {
            bundle.putString(IntentExtra.TIMELINE_TYPE.key, timelineType.save());
        }
        BundleUtils.putNotZero(bundle, IntentExtra.ORIGIN_ID, origin.getId());
        BundleUtils.putNotZero(bundle, IntentExtra.USER_ID, userId);
        BundleUtils.putNotEmpty(bundle, IntentExtra.USER_NAME, userName);
        BundleUtils.putNotEmpty(bundle, IntentExtra.SEARCH_QUERY, searchQuery);
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        if (TextUtils.isEmpty(this.userName) && !TextUtils.isEmpty(userName)) {
            this.userName = userName;
            changed = true;
        }
    }

    public boolean isUserDifferentFromAccount() {
        return userId != 0 && myAccount.getUserId() != userId;
    }

    public String getYoungestPosition() {
        return youngestPosition;
    }

    public void setYoungestPosition(String youngestPosition) {
        this.youngestPosition = youngestPosition;
        changed = true;
    }

    public long getYoungestItemDate() {
        return youngestItemDate;
    }

    public void setYoungestItemDate(long youngestItemDate) {
        this.youngestItemDate = youngestItemDate;
        changed = true;
    }

    public long getYoungestSyncedDate() {
        return youngestSyncedDate;
    }

    public void setYoungestSyncedDate(long youngestSyncedDate) {
        this.youngestSyncedDate = youngestSyncedDate;
        changed = true;
    }

    public long getVisibleItemId() {
        return visibleItemId;
    }

    public void setVisibleItemId(long visibleItemId) {
        if (this.visibleItemId != visibleItemId) {
            changed = true;
            this.visibleItemId = visibleItemId;
        }
    }

    public int getVisibleY() {
        return visibleY;
    }

    public void setVisibleY(int visibleY) {
        if (this.visibleY != visibleY) {
            changed = true;
            this.visibleY = visibleY;
        }
    }

    public long getVisibleYoungestDate() {
        return visibleYoungestDate;
    }

    public void setVisibleYoungestDate(long visibleYoungestDate) {
        if (this.visibleYoungestDate != visibleYoungestDate) {
            changed = true;
            this.visibleYoungestDate = visibleYoungestDate;
        }
    }

    public boolean isSynced() {
        return synced;
    }

    public boolean isChanged() {
        return changed;
    }

    public void setSynced(boolean synced) {
        if (this.synced != synced && isSyncable()) {
            this.synced = synced;
            changed = true;
        }
    }

    public void setDisplayedInSelector(boolean displayedInSelector) {
        if (this.displayedInSelector != displayedInSelector) {
            this.displayedInSelector = displayedInSelector;
            changed = true;
        }
    }

    public Timeline cloneForAccount(MyAccount ma) {
        long userId = getMyAccount().getUserId() != getUserId() ? getUserId() : 0;
        Timeline timeline = new Timeline(getTimelineType(), ma, userId, null);
        timeline.setSearchQuery(getSearchQuery());
        return timeline;
    }

    public Timeline cloneForOrigin(Origin origin) {
        Timeline timeline = new Timeline(getTimelineType(), null, 0, origin);
        timeline.setSearchQuery(getSearchQuery());
        return timeline;
    }

    public void onSyncEnded(CommandResult result) {
        if (result.hasError()) {
            syncFailedDate = System.currentTimeMillis();
            if (!TextUtils.isEmpty(result.getMessage())) {
                errorMessage = result.getMessage();
            }
            syncFailedTimesCount++;
            syncFailedTimesCountTotal++;
        } else {
            syncedDate = System.currentTimeMillis();
            syncedTimesCount++;
            syncedTimesCountTotal++;
        }
        if (result.getMessagesAdded() > 0) {
            newItemsCount += result.getMessagesAdded();
            newItemsCountTotal += result.getMessagesAdded();
        }
        changed = true;
    }

    public long getSyncedDate() {
        return syncedDate;
    }

    public boolean isSyncable() {
        return !isCombined && timelineType.isSyncable();
    }
}
