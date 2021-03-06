/* 
 * Copyright (c) 2013 yvolk (Yuri Volkov), http://yurivolkov.com
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

package org.andstatus.app.net.social;

import android.net.Uri;

import org.andstatus.app.account.AccountDataReaderEmpty;
import org.andstatus.app.account.AccountName;
import org.andstatus.app.account.MyAccount;
import org.andstatus.app.context.MyContextHolder;
import org.andstatus.app.context.TestSuite;
import org.andstatus.app.data.DataUpdater;
import org.andstatus.app.data.DownloadStatus;
import org.andstatus.app.data.MyQuery;
import org.andstatus.app.data.OidEnum;
import org.andstatus.app.net.http.HttpConnectionMock;
import org.andstatus.app.net.http.OAuthClientKeys;
import org.andstatus.app.net.social.Connection.ApiRoutineEnum;
import org.andstatus.app.origin.Origin;
import org.andstatus.app.origin.OriginConnectionData;
import org.andstatus.app.service.CommandData;
import org.andstatus.app.service.CommandEnum;
import org.andstatus.app.service.CommandExecutionContext;
import org.andstatus.app.util.TriState;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static org.andstatus.app.context.DemoData.demoData;
import static org.andstatus.app.util.MyHtmlTest.twitterBodyHtml;
import static org.andstatus.app.util.MyHtmlTest.twitterBodyToPost;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class ConnectionTwitterTest {
    private Connection connection;
    private HttpConnectionMock httpConnection;
    private OriginConnectionData connectionData;

    @Before
    public void setUp() throws Exception {
        TestSuite.initializeWithAccounts(this);

        TestSuite.setHttpConnectionMockClass(HttpConnectionMock.class);
        Origin origin = MyContextHolder.get().origins().fromName(demoData.twitterTestOriginName);

        connectionData = OriginConnectionData.fromAccountName(
                AccountName.fromOriginAndUsername(origin, demoData.twitterTestAccountUsername),
                TriState.UNKNOWN);
        connectionData.setAccountActor(demoData.getAccountActorByOid(demoData.twitterTestAccountActorOid));
        connectionData.setDataReader(new AccountDataReaderEmpty());
        connection = connectionData.newConnection();
        httpConnection = (HttpConnectionMock) connection.http;

        httpConnection.data.originUrl = origin.getUrl();
        httpConnection.data.oauthClientKeys = OAuthClientKeys.fromConnectionData(httpConnection.data);

        if (!httpConnection.data.oauthClientKeys.areKeysPresent()) {
            httpConnection.data.oauthClientKeys.setConsumerKeyAndSecret("keyForGetTimelineForTw", "thisIsASecret341232");
        }
        TestSuite.setHttpConnectionMockClass(null);
    }

    @Test
    public void testGetTimeline() throws IOException {
        httpConnection.addResponse(org.andstatus.app.tests.R.raw.twitter_home_timeline);
        
        List<AActivity> timeline = connection.getTimeline(ApiRoutineEnum.HOME_TIMELINE,
                new TimelinePosition("380925803053449216") , TimelinePosition.EMPTY, 20, connectionData.getAccountActor().oid);
        assertNotNull("timeline returned", timeline);
        int size = 4;
        assertEquals("Number of items in the Timeline", size, timeline.size());

        int ind = 0;
        AActivity activity = timeline.get(ind);
        String hostName = demoData.getTestOriginHost(demoData.twitterTestOriginName).replace("api.", "");
        assertEquals("Posting note", AObjectType.NOTE, activity.getObjectType());
        assertEquals("Timeline position", "381172771428257792", activity.getTimelinePosition().getPosition());
        assertEquals("Note Oid", "381172771428257792", activity.getNote().oid);
        assertEquals("MyAccount", connectionData.getAccountActor(), activity.accountActor);
        assertEquals("Favorited " + activity, TriState.TRUE, activity.getNote().getFavoritedBy(activity.accountActor));
        Actor author = activity.getAuthor();
        assertEquals("Oid", "221452291", author.oid);
        assertEquals("Username", "Know", author.getUsername());
        assertEquals("WebFinger ID", "know@" + hostName, author.getWebFingerId());
        assertEquals("Display name", "Just so you Know", author.getRealName());
        assertEquals("Description", "Unimportant facts you'll never need to know. Legally responsible publisher: @FUN", author.getSummary());
        assertEquals("Location", "Library of Congress", author.location);
        assertEquals("Profile URL", "https://" + hostName + "/Know", author.getProfileUrl());
        assertEquals("Homepage", "http://t.co/4TzphfU9qt", author.getHomepage());
        assertEquals("Avatar URL", "https://si0.twimg.com/profile_images/378800000411110038/a8b7eced4dc43374e7ae21112ff749b6_normal.jpeg", author.getAvatarUrl());
        assertEquals("Banner URL", Uri.parse("https://pbs.twimg.com/profile_banners/221452291/1377270845"),
                author.endpoints.getFirst(ActorEndpointType.BANNER));
        assertEquals("Notes count", 1592, author.notesCount);
        assertEquals("Favorites count", 163, author.favoritesCount);
        assertEquals("Following (friends) count", 151, author.followingCount);
        assertEquals("Followers count", 1878136, author.followersCount);
        assertEquals("Created at", connection.parseDate("Tue Nov 30 18:17:25 +0000 2010"), author.getCreatedDate());
        assertEquals("Updated at", 0, author.getUpdatedDate());
        assertEquals("Actor is author", author.oid, activity.getActor().oid);

        ind++;
        activity = timeline.get(ind);
        Note note = activity.getNote();
        assertTrue("Note is loaded", note.getStatus() == DownloadStatus.LOADED);
        assertTrue("Does not have a recipient", note.audience().isEmpty());
        assertNotEquals("Is a Reblog " + activity, ActivityType.ANNOUNCE, activity.type);
        assertTrue("Is a reply", note.getInReplyTo().nonEmpty());
        assertEquals("Reply to the note id", "17176774678", note.getInReplyTo().getNote().oid);
        assertEquals("Reply to the note by actorOid", demoData.twitterTestAccountActorOid, note.getInReplyTo().getAuthor().oid);
        assertTrue("Reply status is unknown", note.getInReplyTo().getNote().getStatus() == DownloadStatus.UNKNOWN);
        assertEquals("Favorited by me " + activity, TriState.UNKNOWN, activity.getNote().getFavoritedBy(activity.accountActor));
        String startsWith = "@t131t";
        assertEquals("Body of this note starts with", startsWith, note.getContent().substring(0, startsWith.length()));

        ind++;
        activity = timeline.get(ind);
        note = activity.getNote();
        assertTrue("Does not have a recipient", note.audience().isEmpty());
        assertEquals("Is not a Reblog " + activity, ActivityType.ANNOUNCE, activity.type);
        assertTrue("Is not a reply", note.getInReplyTo().isEmpty());
        assertEquals("Reblog of the note id", "315088751183409153", note.oid);
        assertEquals("Author of reblogged note oid", "442756884", activity.getAuthor().oid);
        assertEquals("Reblog id", "383295679507869696", activity.getTimelinePosition().getPosition());
        assertEquals("Reblogger oid", "111911542", activity.getActor().oid);
        assertEquals("Favorited by me " + activity, TriState.UNKNOWN, activity.getNote().getFavoritedBy(activity.accountActor));
        startsWith = "This AndStatus application";
        assertEquals("Body of reblogged note starts with", startsWith,
                note.getContent().substring(0, startsWith.length()));
        Date date = TestSuite.utcTime(2013, Calendar.SEPTEMBER, 26, 18, 23, 5);
        assertEquals("Reblogged at Thu Sep 26 18:23:05 +0000 2013 (" + date + ") " + activity, date,
                TestSuite.utcTime(activity.getUpdatedDate()));
        date = TestSuite.utcTime(2013, Calendar.MARCH, 22, 13, 13, 7);
        assertEquals("Reblogged note created at Fri Mar 22 13:13:07 +0000 2013 (" + date + ")" + note,
                date, TestSuite.utcTime(note.getUpdatedDate()));

        ind++;
        activity = timeline.get(ind);
        note = activity.getNote();
        assertTrue("Does not have a recipient", note.audience().isEmpty());
        assertNotEquals("Is a Reblog " + activity, ActivityType.ANNOUNCE, activity.type);
        assertTrue("Is not a reply", note.getInReplyTo().isEmpty());
        assertEquals("Favorited by me " + activity, TriState.UNKNOWN, activity.getNote().getFavoritedBy(activity.accountActor));
        assertEquals("Author's oid is actor oid of this account", connectionData.getAccountActor().oid, activity.getAuthor().oid);
        startsWith = "And this is";
        assertEquals("Body of this note starts with", startsWith, note.getContent().substring(0, startsWith.length()));
    }

    @Test
    public void getNoteWithAttachment() throws IOException {
        httpConnection.addResponse(org.andstatus.app.tests.R.raw.twitter_note_with_media);

        Note note = connection.getNote("503799441900314624").getNote();
        assertNotNull("note returned", note);
        assertEquals("has attachment", 1, note.attachments.size());
        assertEquals("attachment",  Attachment.fromUri("https://pbs.twimg.com/media/Bv3a7EsCAAIgigY.jpg"),
                note.attachments.list.get(0));
        assertNotSame("attachment", Attachment.fromUri("https://pbs.twimg.com/media/Bv4a7EsCAAIgigY.jpg"),
                note.attachments.list.get(0));
    }

    @Test
    public void getNoteWithEscapedHtmlTag() throws IOException {
        httpConnection.addResponse(org.andstatus.app.tests.R.raw.twitter_note_with_escaped_html_tag);

        String body = "Update: Streckensperrung zw. Berliner Tor &lt;&gt; Bergedorf. Ersatzverkehr mit Bussen und Taxis " +
                "Störungsdauer bis ca. 10 Uhr. #hvv #sbahnhh";
        AActivity activity = connection.getNote("834306097003581440");
        assertEquals("No note returned " + activity, AObjectType.NOTE, activity.getObjectType());
        Note note = activity.getNote();
        assertEquals("Body of this note", body, note.getContent());
        assertEquals("Body of this note", ",update,streckensperrung,zw,berliner,tor,bergedorf,ersatzverkehr,mit,bussen," +
                "und,taxis,störungsdauer,bis,ca,10,uhr,hvv,#hvv,sbahnhh,#sbahnhh,", note.getContentToSearch());

        MyAccount ma = demoData.getMyAccount(connectionData.getAccountName().toString());
        CommandExecutionContext executionContext = new CommandExecutionContext(
                CommandData.newAccountCommand(CommandEnum.GET_NOTE, ma));
        DataUpdater di = new DataUpdater(executionContext);
        di.onActivity(activity);
        assertNotEquals("Note was not added " + activity, 0, note.noteId);
        assertNotEquals("Activity was not added " + activity, 0, activity.getId());
    }

    @Test
    public void getNoteWithEscapedChars() throws IOException {
        httpConnection.addResponse(org.andstatus.app.tests.R.raw.twitter_note_with_escaped_chars);

        String contentToSearch = ",testing,if,and,what,is,escaped,in,a,tweet," +
                "1,less-than,sign,and,escaped,&lt," +
                "2,greater-than,sign,and,escaped,&gt," +
                "3,ampersand,&,and,escaped,&amp," +
                "4,apostrophe," +
                "5,br,html,tag,br,/,and,without,/,br,";

        AActivity activity = connection.getNote("1070738478198071296");
        assertEquals("No note returned " + activity, AObjectType.NOTE, activity.getObjectType());
        Note note = activity.getNote();
        assertEquals("Body of this note", twitterBodyHtml, note.getContent());
        assertEquals("Body as sent", twitterBodyToPost, note.getContentToPost());
        assertEquals("Content to Search of this note", contentToSearch, note.getContentToSearch());

        MyAccount ma = demoData.getMyAccount(connectionData.getAccountName().toString());
        CommandExecutionContext executionContext = new CommandExecutionContext(
                CommandData.newAccountCommand(CommandEnum.GET_NOTE, ma));
        DataUpdater di = new DataUpdater(executionContext);
        di.onActivity(activity);
        assertNotEquals("Note was not added " + activity, 0, note.noteId);
        assertNotEquals("Activity was not added " + activity, 0, activity.getId());
    }

    @Test
    public void follow() throws IOException {
        httpConnection.addResponse(org.andstatus.app.tests.R.raw.twitter_follow);

        String actorOid = "96340134";
        AActivity activity = connection.follow(actorOid, true);
        assertEquals("No actor returned " + activity, AObjectType.ACTOR, activity.getObjectType());
        Actor friend = activity.getObjActor();
        assertEquals("Wrong username returned " + activity, "LPirro93", friend.getUsername());

        MyAccount ma = demoData.getMyAccount(connectionData.getAccountName().toString());
        CommandExecutionContext executionContext = new CommandExecutionContext(
                CommandData.actOnActorCommand(CommandEnum.FOLLOW, ma, 123, ""));
        DataUpdater di = new DataUpdater(executionContext);
        di.onActivity(activity);
        long friendId = MyQuery.oidToId(MyContextHolder.get(), OidEnum.ACTOR_OID, ma.getOriginId(), actorOid);

        assertNotEquals("Followed Actor was not added " + activity, 0, friendId);
        assertNotEquals("Activity was not added " + activity, 0, activity.getId());
    }


}
