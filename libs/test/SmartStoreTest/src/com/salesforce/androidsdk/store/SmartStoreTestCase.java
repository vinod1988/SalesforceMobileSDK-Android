/*
 * Copyright (c) 2012, salesforce.com, inc.
 * All rights reserved.
 * Redistribution and use of this software in source and binary forms, with or
 * without modification, are permitted provided that the following conditions
 * are met:
 * - Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * - Neither the name of salesforce.com, inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission of salesforce.com, inc.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.salesforce.androidsdk.store;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.salesforce.androidsdk.smartstore.store.DBHelper;
import com.salesforce.androidsdk.smartstore.store.DBOpenHelper;
import com.salesforce.androidsdk.smartstore.store.IndexSpec;
import com.salesforce.androidsdk.smartstore.store.SmartStore;
import com.salesforce.androidsdk.util.test.JSONTestHelper;

import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SQLiteOpenHelper;

import android.content.Context;
import android.database.Cursor;
import android.test.InstrumentationTestCase;

/**
 * Abstract super class for smart store tests
 *
 */
public abstract class SmartStoreTestCase extends InstrumentationTestCase {

	protected Context targetContext;
	protected SQLiteOpenHelper dbOpenHelper;
	protected SmartStore store;

	@Override
	public void setUp() throws Exception {
		super.setUp();
		targetContext = getInstrumentation().getTargetContext();
		dbOpenHelper = DBOpenHelper.getOpenHelper(targetContext, null);
		DBHelper.getInstance(dbOpenHelper.getWritableDatabase(getPasscode())).reset(targetContext, null);
		store = new SmartStore(dbOpenHelper, getPasscode());
		store.dropAllSoups();
	}

	protected abstract String getPasscode();

	@Override
	protected void tearDown() throws Exception {
		dbOpenHelper.close();
		store.dropAllSoups();
		DBOpenHelper.deleteDatabase(targetContext, null);
		super.tearDown();
	}

	/**
	 * Helper method to check that a table exists in the database
	 * @param tableName
	 * @return
	 */
	protected boolean hasTable(String tableName) {
		Cursor c = null;
		final SQLiteDatabase db = dbOpenHelper.getWritableDatabase(getPasscode());
		try {
			c = DBHelper.getInstance(db).query(db, "sqlite_master", null, null, null, "type = ? and name = ?", "table", tableName);
			return c.getCount() == 1;
		}
		finally {
			safeClose(c);
		}
	}

	/**
	 * Helper method to check columns of table
	 * @param tableName
	 * @param expectedColumnNames
     */
	protected void checkColumns(String tableName, List<String> expectedColumnNames) {
		Cursor c = null;
		final SQLiteDatabase db = dbOpenHelper.getWritableDatabase(getPasscode());
		try {
			List<String> actualColumnNames = new ArrayList<>();
			c = db.rawQuery(String.format("PRAGMA table_info(%s)", tableName), null);
			while(c.moveToNext()) {
				actualColumnNames.add(c.getString(1));
			}
			JSONTestHelper.assertSameJSONArray("Wrong columns", new JSONArray(expectedColumnNames), new JSONArray(actualColumnNames));
		}
		catch (Exception e) {
			fail("Failed with error:" + e.getMessage());
		}
		finally {
			safeClose(c);
		}
	}

    /**
     * Helper method to check index specs on a soup
     */
    protected void checkIndexSpecs(String soupName, IndexSpec[] expectedIndexSpecs) throws JSONException {
        final IndexSpec[] actualIndexSpecs = store.getSoupIndexSpecs(soupName);
        JSONTestHelper.assertSameJSONArray("Wrong index specs", IndexSpec.toJSON(expectedIndexSpecs), IndexSpec.toJSON(actualIndexSpecs));
    }

	/**
	 * Helper method to check create table statement that was used to create a table
	 * @param tableName
	 * @param subStringExpected that created the indexes
	 */
	protected void checkCreateTableStatement(String tableName, String subStringExpected) {
		Cursor c = null;
		final SQLiteDatabase db = dbOpenHelper.getWritableDatabase(getPasscode());
		try {
			List<String> actualSqlStatements = new ArrayList<>();
			c = db.rawQuery(String.format("SELECT sql FROM sqlite_master WHERE type='table' AND tbl_name='%s' ORDER BY name", tableName), null);
			assertEquals("Expected one statement", 1, c.getCount());
			c.moveToFirst();
			String actualStatement = c.getString(0);
			assertTrue("Wrong statement: " + actualStatement, actualStatement.contains(subStringExpected));
		}
		catch (Exception e) {
			fail("Failed with error:" + e.getMessage());
		}
		finally {
			safeClose(c);
		}
	}

    /**
     * Helper method to check db indexes on a table
     * @param tableName
     * @param expectedSqlStatements that created the indexes
     */
    protected void checkDatabaseIndexes(String tableName, List<String> expectedSqlStatements) {
        Cursor c = null;
        final SQLiteDatabase db = dbOpenHelper.getWritableDatabase(getPasscode());
        try {
            List<String> actualSqlStatements = new ArrayList<>();
            c = db.rawQuery(String.format("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='%s' ORDER BY name", tableName), null);
            while(c.moveToNext()) {
                actualSqlStatements.add(c.getString(0));
            }
            JSONTestHelper.assertSameJSONArray("Wrong indexes", new JSONArray(expectedSqlStatements), new JSONArray(actualSqlStatements));
        }
        catch (Exception e) {
            fail("Failed with error:" + e.getMessage());
        }
        finally {
            safeClose(c);
        }
    }

    /**
     * Get explain query plan of last query run and make sure the given index was used
	 * @param soupName
     * @param index the index of the index spec in the array of index specs passed to register soup
	 * @param covering
	 * @param dbOperation e.g. SCAN or SEARCH
	 */
    public void checkExplainQueryPlan(String soupName, int index, boolean covering, String dbOperation) throws JSONException {
        JSONObject explainQueryPlan = store.getLastExplainQueryPlan();
        String soupTableName = getSoupTableName(soupName);
        String indexName = soupTableName + "_" + index + "_idx";
        String expectedDetailPrefix = String.format("%s TABLE %s USING %sINDEX %s", dbOperation, soupTableName, covering ? "COVERING " : "", indexName);
        String detail = explainQueryPlan.getJSONArray(DBHelper.EXPLAIN_ROWS).getJSONObject(0).getString("detail");

        assertTrue("Wrong query plan:" + detail, detail.startsWith(expectedDetailPrefix));
    }

	public void checkFileSystem(String soupName, long[] expectedIds, boolean shouldExist) {
		String soupTableName = getSoupTableName(soupName);
		for (long expectedId : expectedIds) {
			File file = ((DBOpenHelper) dbOpenHelper).getSoupBlobFile(soupTableName, expectedId);
			if (shouldExist) {
				assertTrue("External file for " + expectedId + " should exist", file.exists());
			}
			else {
				assertFalse("External file for " + expectedId + " should not exist", file.exists());
			}
		}
	}

	/**
	 * Close cursor if not null
	 * @param c
	 */
	protected void safeClose(Cursor c) {
		if (c != null) {
			c.close();
		}
	}

	/**
	 * @param soupName
	 * @return table name for soup
	 */
	protected String getSoupTableName(String soupName) {
		final SQLiteDatabase db = dbOpenHelper.getWritableDatabase(getPasscode());
		return DBHelper.getInstance(db).getSoupTableName(db, soupName);
	}

	/**
	 * @param soupElt
	 * @return _soupEntryId field value
	 * @throws JSONException
	 */
	public static long idOf(JSONObject soupElt) throws JSONException {
		return soupElt.getLong(SmartStore.SOUP_ENTRY_ID);
	}

	/**
	 * Registers a soup with the given name and index specs. Can be overridden if extra features are desired.
	 */
	protected void registerSoup(SmartStore store, String soupName, IndexSpec[] indexSpecs) {
		store.registerSoup(soupName, indexSpecs);
	}
}