/*
 * Copyright (C) 2015 yvolk (Yuri Volkov), http://yurivolkov.com
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

package org.andstatus.app.service;

import android.net.Uri;

import org.andstatus.app.context.MyContextHolder;
import org.andstatus.app.net.http.ConnectionException;
import org.andstatus.app.net.social.ConnectionEmpty;
import org.andstatus.app.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Connection to local resources
 */
public class ConnectionLocal extends ConnectionEmpty {
    @Override
    public void downloadFile(String uri, File file) throws ConnectionException {
        try {
            InputStream ins = MyContextHolder.get().context().getContentResolver().openInputStream(Uri.parse(uri));
            FileUtils.readStreamToFile(ins, file);
        } catch (IOException e) {
            throw ConnectionException.hardConnectionException("mediaUri='" + uri + "'", e);
        } catch (SecurityException e) {
            throw ConnectionException.hardConnectionException("mediaUri='" + uri + "'", e);
        }
    }
}
