package controller;

import java.util.ArrayList;
import java.util.Calendar;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import com.sforce.soap.enterprise.Address;
import com.sforce.soap.enterprise.DeleteResult;
import com.sforce.soap.enterprise.EnterpriseConnection;
import com.sforce.soap.enterprise.Error;
import com.sforce.soap.enterprise.QueryResult;
import com.sforce.soap.enterprise.SaveResult;
import com.sforce.soap.enterprise.UpsertResult;
import com.sforce.soap.enterprise.sobject.Account;
import com.sforce.soap.enterprise.sobject.Contact;
import com.sforce.soap.enterprise.sobject.Contact_Diary__c;
import com.sforce.soap.enterprise.sobject.SObject;
import com.sforce.soap.enterprise.sobject.Staff_Hours__c;
import com.sforce.soap.enterprise.sobject.Student_Attendance__c;
import com.sforce.soap.enterprise.sobject.Workshop_by_Teacher__c;
import com.sforce.ws.ConnectionException;

import model.AttendanceEventModel;
import model.GraduationModel;
import model.LocationLookup;
import model.LogDataModel;
import model.MySqlDatabase;
import model.MySqlDbImports;
import model.MySqlDbLogging;
import model.SalesForceAttendanceModel;
import model.SalesForceStaffHoursModel;
import model.StaffMemberModel;
import model.StudentImportModel;
import model.StudentNameModel;

public class UpdateRecordsInSalesForce {
	private static final int MAX_NUM_UPSERT_RECORDS = 200;
	private static final int WEEKS_TO_UPDATE_WORKSHOP_GRADS = 2;
	private static final String RECORD_TYPE_ID_STUDENT = "012o000000089x0AAA";
	private static final String RECORD_TYPE_ID_ADULT = "012o000000089wzAAA";
	private static final int MAX_SALESFORCE_FIELD_LENGTH = 100;
	private static final int MAX_BILLING_NOTES_FIELD_LENGTH = 100;
	private static final int MAX_EMOJI_FIELD_LENGTH = 35;
	private static final String ADMIN_STAFF_CLIENT_ID = "999999";
	private static final String ADMIN_STAFF_NAME = "Staff Admin";
	private static final double PASSING_GRADE_FOR_CLASSES = 70.0;
	private static final double PASSING_GRADE_FOR_ORACLE = 65.0;

	private MySqlDbImports dbImports;
	private EnterpriseConnection connection;
	private GetRecordsFromSalesForce getRecords;

	private boolean attendanceUpsertError = false;
	
	private static final String[] gitEmojis = {":smile:", ":sweat_smile:", ":thumbsup:", ":sunglasses:", ":sleeping:", ":confused:", ":astonished:", 
			":neutral_face:", ":unamused:", ":cry:", ":tada:", ":question:", ":100:", ":+1:", ":-1:" };

	public UpdateRecordsInSalesForce(MySqlDatabase mySqlDb, MySqlDbImports dbImports, EnterpriseConnection connection,
			GetRecordsFromSalesForce getRecords) {
		this.dbImports = dbImports;
		this.connection = connection;
		this.getRecords = getRecords;
	}

	public void updateStudents(ArrayList<StudentImportModel> pike13Students,
			ArrayList<StudentImportModel> pike13Managers, ArrayList<Account> sfAccounts) {
		ArrayList<Contact> contactList = new ArrayList<Contact>();

		try {
			for (int i = 0; i < pike13Students.size(); i++) {
				// Add each Pike13 client record to SalesForce list
				StudentImportModel student = pike13Students.get(i);

				// Ignore school clients!
				if (student.getFullName().equalsIgnoreCase("gompers prep")
						|| student.getFullName().equalsIgnoreCase("wilson middle school")
						|| student.getFullName().equalsIgnoreCase("nativity prep academy")
						|| student.getFullName().equalsIgnoreCase("sample customer")
						|| student.getFullName().equalsIgnoreCase("barrio logan college institute")
						|| student.getFullName().equalsIgnoreCase("monroe middle school")
						|| student.getFirstName().startsWith("HOLIDAY")
						|| student.getFullName().startsWith("Need Assistant")
						|| student.getFullName().startsWith("Needs Assistant")
						|| student.getFullName().toLowerCase().startsWith("needs teacher")
						|| student.getFullName().toLowerCase().startsWith("sub teacher")
						|| student.getFullName().startsWith("Accepting Students"))
					continue;

				// Check for account manager names
				if (student.getAccountMgrNames() == null || student.getAccountMgrNames().equals("")) {
					// Student has no Pike13 account manager, so error
					MySqlDbLogging.insertLogData(LogDataModel.MISSING_PIKE13_ACCT_MGR_FOR_CLIENT,
							new StudentNameModel(student.getFirstName(), student.getLastName(), true),
							student.getClientID(), " " + student.getFullName());
					continue;
				}

				// Find account manager in Pike13 list so we can parse first/last names
				String accountMgrName = ListUtilities.getFirstNameInString(student.getAccountMgrNames());
				StudentImportModel acctMgrModel = ListUtilities.findAcctManagerInList(student, accountMgrName,
						pike13Managers);
				if (acctMgrModel == null) {
					// Pike13 account manager not found? This should not happen!
					MySqlDbLogging.insertLogData(LogDataModel.MISSING_PIKE13_ACCT_MGR_FOR_CLIENT,
							new StudentNameModel(student.getFirstName(), student.getLastName(), true),
							student.getClientID(), " " + student.getFullName() + ", manager " + accountMgrName);
					continue;
				}

				// Find account in SalesForce
				String acctFamilyName = camelCase(
						acctMgrModel.getLastName() + " " + acctMgrModel.getFirstName() + " Family");
				Account account = ListUtilities.findAccountInSalesForceList(acctFamilyName, acctMgrModel, sfAccounts);

				if (account.getName().equals("")) {
					// SalesForce account does not yet exist, so create
					account.setName(acctFamilyName);
					account.setType("Family");
					if (!createAccountRecord(student.getFirstName(), student.getLastName(), student.getClientID(),
							account))
						continue;

					// Now that account has been created, need to get account from SF again
					account = getRecords.getSalesForceAccountByName(acctFamilyName);
					if (account == null || account.getName().equals(""))
						continue;

					// Add to list in case more dependents belong to this account
					sfAccounts.add(account);
				}

				// Create contact and add to list
				contactList.add(createContactRecord(false, student, account, pike13Students));
			}

			upsertContactRecordList(contactList, "student");

		} catch (Exception e) {
			if (e.getMessage() == null || e.getMessage().equals("null")) {
				MySqlDbLogging.insertLogData(LogDataModel.SF_CLIENT_IMPORT_ERROR, new StudentNameModel("", "", false),
						0, "");
				e.printStackTrace();
			} else
				MySqlDbLogging.insertLogData(LogDataModel.SF_CLIENT_IMPORT_ERROR, new StudentNameModel("", "", false),
						0, ": " + e.getMessage());
		}
	}

	public void updateAdults(ArrayList<StudentImportModel> pike13Students, ArrayList<StudentImportModel> pike13Adults,
			ArrayList<Account> sfAccounts) {
		ArrayList<Contact> contactList = new ArrayList<Contact>();
		ArrayList<Account> accountList = new ArrayList<Account>();

		try {
			for (int i = 0; i < pike13Adults.size(); i++) {
				// Add each Pike13 client record to SalesForce list
				StudentImportModel adult = pike13Adults.get(i);

				// Ignore test accounts
				if (adult.getFirstName().startsWith("HOLIDAY")
						|| adult.getFullName().equalsIgnoreCase("sample customer")
						|| adult.getFullName().startsWith("Need Assistant")
						|| adult.getFullName().startsWith("Needs Assistant")
						|| adult.getFullName().toLowerCase().startsWith("needs teacher")
						|| adult.getFullName().toLowerCase().startsWith("sub teacher")
						|| adult.getFullName().startsWith("Accepting Student"))
					continue;

				// Get 1st dependent name, use this to get SalesForce Account
				String dependentName = ListUtilities.getFirstNameInString(adult.getDependentNames());
				Account account = ListUtilities.findAccountNameInList(dependentName, adult.getAccountID(), pike13Students, pike13Adults,
						sfAccounts);

				if (account.getName().equals(""))
					// Dependent is either hidden/deleted/inactive or this is test account/school
					continue;

				// Create contact and add to list
				contactList.add(createContactRecord(true, adult, account, pike13Students));
				if (account.getName().equals(adult.getLastName() + " " + adult.getFirstName() + " Family")) {
					accountList.add(updateAccountRecord(adult, account));
				}
			}

			upsertContactRecordList(contactList, "adult");
			upsertAccountRecordList(accountList, "account");

		} catch (Exception e) {
			if (e.getMessage() == null || e.getMessage().equals("null")) {
				MySqlDbLogging.insertLogData(LogDataModel.SF_CLIENT_IMPORT_ERROR, new StudentNameModel("", "", false),
						0, "");
				e.printStackTrace();
			} else
				MySqlDbLogging.insertLogData(LogDataModel.SF_CLIENT_IMPORT_ERROR, new StudentNameModel("", "", false),
						0, ": " + e.getMessage());
		}
	}

	public void updateAttendance(ArrayList<SalesForceAttendanceModel> pike13Attendance,
			ArrayList<AttendanceEventModel> dbAttendance, ArrayList<Contact> contacts, ArrayList<Contact> allContacts,
			ArrayList<StudentImportModel> pike13Students, ArrayList<StaffMemberModel> staffMembers) {
		ArrayList<Student_Attendance__c> recordList = new ArrayList<Student_Attendance__c>();
		ArrayList<Contact> workShopGrads = new ArrayList<Contact>();
		ArrayList<Workshop_by_Teacher__c> wshopByTeacherList = new ArrayList<Workshop_by_Teacher__c>();
		ArrayList<AttendanceEventModel> attendLevelChanges = new ArrayList<AttendanceEventModel>();
		String startBillingDate = new DateTime().withZone(DateTimeZone.forID("America/Los_Angeles")).minusDays(7)
				.toString("yyyy-MM-dd");

		try {
			for (int i = 0; i < pike13Attendance.size(); i++) {
				// Add each Pike13 attendance record to SalesForce list
				SalesForceAttendanceModel inputModel = pike13Attendance.get(i);

				// Check for Visit ID null or blank. Visit ID is main key for upsert.
				if (checkForVisitIdNull(inputModel))
					continue;

				// Cannot add attendance for contacts that do not exist!
				Contact c = ListUtilities.findClientIDInList(LogDataModel.MISSING_SF_CONTACT_FOR_ATTENDANCE,
						inputModel.getClientID(), inputModel.getFullName(), inputModel.getEventName(), contacts);
				if (c == null)
					continue;

				// Get contacts with required data fields
				Contact contactWithData = ListUtilities.findClientIDInList(-1, inputModel.getClientID(),
						inputModel.getFullName(), "", allContacts);

				// Report error if event name is blank
				String locCode = null;
				String eventName2 = null;
				if (inputModel.getEventName() == null || inputModel.getEventName().equals("")) {
					if (contactWithData.getContact_Type__c() != null
							&& contactWithData.getContact_Type__c().equals("Student")) {
						MySqlDbLogging.insertLogData(LogDataModel.BLANK_EVENT_NAME_FOR_ATTENDANCE,
								new StudentNameModel(inputModel.getFullName(), "", false),
								Integer.parseInt(inputModel.getClientID()),
								" on " + inputModel.getServiceDate() + ", " + inputModel.getServiceName());
						eventName2 = inputModel.getServiceName();
					}
				} else
					locCode = ListUtilities.findLocCodeInList(inputModel.getEventName());

				// Report error if location code is invalid
				if (locCode == null) {
					// Location code not valid, report error if '@' in event name
					if (inputModel.getEventName() != null && inputModel.getEventName().contains("@"))
						MySqlDbLogging.insertLogData(LogDataModel.ATTENDANCE_LOC_CODE_INVALID,
								new StudentNameModel(inputModel.getFullName(), "", false),
								Integer.parseInt(inputModel.getClientID()),
								" for event " + inputModel.getEventName() + " (" + inputModel.getServiceDate() + ")");

				} else if (!LocationLookup.findLocationCodeMatch(locCode, inputModel.getLocation())) {
					// Location code is valid, but does not match event location
					MySqlDbLogging.insertLogData(LogDataModel.ATTENDANCE_LOC_CODE_MISMATCH,
							new StudentNameModel("", "", false), 0,
							" for event " + inputModel.getEventName() + ", " + inputModel.getLocation());
				}

				Student_Attendance__c a = new Student_Attendance__c();
				a.setContact__r(c);
				if (eventName2 != null)
					a.setEvent_Name__c(eventName2);
				else
					a.setEvent_Name__c(inputModel.getEventName());

				a.setService_Name__c(inputModel.getServiceName());
				if (inputModel.getEventType() != null) {
					if (inputModel.getEventType().length() > 6) {
						a.setEvent_Type__c(inputModel.getEventType().substring(0, 5));
						a.setEvent_Type_Sub__c(inputModel.getEventType().substring(6));
					} else {
						a.setEvent_Type__c(inputModel.getEventType());
						a.setEvent_Type_Sub__c("");
					}
				}
				a.setStatus__c(inputModel.getStatus());
				a.setSchedule_id__c(inputModel.getScheduleID());
				a.setVisit_Id__c(inputModel.getVisitID());
				a.setLocation__c(inputModel.getLocation());

				AttendanceEventModel dbAttend = ListUtilities.findAttendanceEventInList(inputModel.getVisitID(),
						dbAttendance);
				if (dbAttend != null) {
					if (!dbAttend.getGithubComments().equals("")) {
						a.setNote__c(dbAttend.getGithubComments());
						a.setEmoji_Feedback__c(getEmojiFeedback(dbAttend.getGithubComments().toLowerCase()));
					}
					if (dbAttend.getRepoName() != null && !dbAttend.getRepoName().equals("")) {
						a.setRepo_Name__c(dbAttend.getRepoName());
						updateAttendanceModule(a);
					}
					if (!dbAttend.getGitDescription().equals("")) {
						a.setGit_Description__c(dbAttend.getGitDescription());
						if (a.getEmoji_Feedback__c().equals(""))
							a.setEmoji_Feedback__c(getEmojiFeedback(dbAttend.getGitDescription().toLowerCase()));
					}
				}

				StudentImportModel dbStudent = ListUtilities.findClientIDInPike13List(inputModel.getClientID(),
						pike13Students);
				if (inputModel.getServiceDate().compareTo(startBillingDate) >= 0) {
					if (dbStudent != null) {
						if (dbStudent.getFinancialAidPercent().length() > MAX_BILLING_NOTES_FIELD_LENGTH)
							a.setBilling_Note__c(
									dbStudent.getFinancialAidPercent().substring(0, MAX_BILLING_NOTES_FIELD_LENGTH));
						else
							a.setBilling_Note__c(dbStudent.getFinancialAidPercent());
					}
				}

				String newStaff = parseTeacherString(inputModel.getStaff(), a, staffMembers);
				if (newStaff.length() > MAX_SALESFORCE_FIELD_LENGTH)
					a.setStaff__c(newStaff.substring(0, MAX_SALESFORCE_FIELD_LENGTH));
				else
					a.setStaff__c(newStaff);

				// Extract level from DB attendance, store in 'internal level'
				if (inputModel.getStatus().equals("completed")) {
					updateAttendLevel(attendLevelChanges, inputModel, contactWithData, a, dbAttend);
				}

				a.setService_Date__c(convertDateStringToCalendar(inputModel.getServiceDate()));
				a.setService_TIme__c(inputModel.getServiceTime());
				a.setEnd_Time__c(inputModel.getEndTime());
				a.setHours__c(inputModel.getDuration());

				// Update contact's Intro to Java workshop grad dates
				updateWorkshopGrad(workShopGrads, inputModel);

				recordList.add(a);
				
				if ((a.getEvent_Name__c().toLowerCase().contains("intro to java") || a.getEvent_Name__c().toLowerCase().contains("coding camp")) 
						&& a.getStatus__c().equals("completed"))
					createWorkshopByTeacherRecord(wshopByTeacherList, a, c);
			}

			// Copy up to 200 records to array at a time (max allowed)
			Student_Attendance__c[] recordArray;
			int numRecords = recordList.size();
			int remainingRecords = numRecords;
			int arrayIdx = 0;

			System.out.println(numRecords + " Pike13 attendance records");
			if (numRecords > MAX_NUM_UPSERT_RECORDS)
				recordArray = new Student_Attendance__c[MAX_NUM_UPSERT_RECORDS];
			else
				recordArray = new Student_Attendance__c[numRecords];

			for (int i = 0; i < numRecords; i++) {
				recordArray[arrayIdx] = recordList.get(i);

				arrayIdx++;
				if (arrayIdx == MAX_NUM_UPSERT_RECORDS) {
					upsertAttendanceRecords(recordArray);
					remainingRecords -= MAX_NUM_UPSERT_RECORDS;
					arrayIdx = 0;

					if (remainingRecords > MAX_NUM_UPSERT_RECORDS)
						recordArray = new Student_Attendance__c[MAX_NUM_UPSERT_RECORDS];
					else if (remainingRecords > 0)
						recordArray = new Student_Attendance__c[remainingRecords];
				}
			}

			// Update remaining records in Salesforce.com
			if (arrayIdx > 0)
				upsertAttendanceRecords(recordArray);

		} catch (Exception e) {
			if (e.getMessage() == null || e.getMessage().equals("null")) {
				MySqlDbLogging.insertLogData(LogDataModel.SF_ATTENDANCE_IMPORT_ERROR,
						new StudentNameModel("", "", false), 0, "");
				e.printStackTrace();
			} else
				MySqlDbLogging.insertLogData(LogDataModel.SF_ATTENDANCE_IMPORT_ERROR,
						new StudentNameModel("", "", false), 0, ": " + e.getMessage());
		}

		// Update modified clients to SalesForce
		if (workShopGrads.size() > 0) {
			upsertContactRecordList(workShopGrads, "WorkS grad");
		}
		
		// Update workshops by teacher to SalesForce
		if (wshopByTeacherList.size() > 0) {
			upsertWorkshopByTeacherRecordList(wshopByTeacherList);
		}

		System.out.println(attendLevelChanges.size() + " Attendance completed");
		if (attendLevelChanges.size() > 0) {
			// Update SF attendance state field in SQL DB
			for (AttendanceEventModel a : attendLevelChanges)
				dbImports.updateAttendLevelChanges(a.getVisitID(), a.getState());
		}
	}

	private void updateAttendanceModule(Student_Attendance__c a) {
		// Extract module from repo name
		String repoName = a.getRepo_Name__c().toLowerCase();
		String levelName, moduleName = null;	
		int moduleStartIdx = 0;
		
		// Github classroom names are formatted level-0-module-3 OR level3-module1
		if (repoName.contains("intro-to-java-workshop"))
			return;
		else if (repoName.startsWith("level") && repoName.substring(6).startsWith("-module")) {
			levelName = repoName.substring(5, 6);
			moduleStartIdx = 13;
		} else if (repoName.startsWith("level-") && repoName.substring(7).startsWith("-module")) {
			levelName = repoName.substring(6, 7);
			moduleStartIdx = 14;
		} else if (repoName.startsWith("old-level") && repoName.substring(10).startsWith("-module")) {
			levelName = repoName.substring(9, 10);
			moduleStartIdx = 17;
		} else if (repoName.length() > 10 && repoName.startsWith("level5-0") && repoName.charAt(9) == '-') {
			levelName = repoName.substring(8, 9);
			moduleStartIdx = 8;
		} else if (repoName.startsWith("processingsnake-")) {
			levelName = "2";
			moduleName = "2";
		} else if (repoName.startsWith("league-invaders-")) {
			levelName = "2";
			moduleName = "3";
		} else if (repoName.startsWith("league-level2-game-")) {
			levelName = "2";
			moduleName = "4";
		} else if (repoName.startsWith("level") && 
				(repoName.substring(6).startsWith("-coding-exam-") || repoName.substring(6).startsWith("-codingexam-"))) {
			levelName = repoName.substring(5, 6);
			moduleName = "E";
		} else if (repoName.startsWith("level-") &&
				(repoName.substring(7).startsWith("-coding-exam-") || repoName.substring(7).startsWith("-codingexam-"))) {
			levelName = repoName.substring(6, 7);
			moduleName = "E";
		} else {
			System.out.println("Failed to parse repo: " + a.getContact__r().getFront_Desk_Id__c() + ", " + repoName);
			return; // No matching level in repo name
		}

		if (moduleName == null) {
			if (repoName.charAt(moduleStartIdx) == '-')
				moduleStartIdx++;

			// Now find module
			if (repoName.charAt(moduleStartIdx) >= '0' && repoName.charAt(moduleStartIdx) <= '9' // module #
				&& (repoName.length() == (moduleStartIdx + 1) || repoName.charAt(moduleStartIdx + 1) == '-')) {
				moduleName = repoName.substring(moduleStartIdx, moduleStartIdx + 1);
			}
		}
			
		// Set the values in the attendance record
		a.setGit_Level__c(levelName);
		a.setGit_Module__c(moduleName);
	}

	private String getEmojiFeedback(String description) {
		// Check if description contains any of the emojis in the emoji list
		for (int i = 0; i < gitEmojis.length; i++) {
			if (description.contains(gitEmojis[i]))
				return gitEmojis[i];
		}
		
		// If no standard emoji found, extract any emoji
		int colon1 = description.indexOf(':');
		if (colon1 >= 0 && description.length() > colon1 + 2) {
			int colon2 = description.indexOf(':', colon1 + 1);
			if (colon2 - colon1 > 1) {
				String foundEmoji = description.substring(colon1, colon2 + 1);
				if (foundEmoji.length() > MAX_EMOJI_FIELD_LENGTH)
					foundEmoji = foundEmoji.substring(0, MAX_EMOJI_FIELD_LENGTH);
				
				if (foundEmoji.equals(":nuetral_face:"))
					return ":neutral_face:";
				if (foundEmoji.equals(":smiley:"))
					return ":smile:";
				if (!foundEmoji.contains(" ")) {
					return foundEmoji;
				}
			}
		}
		return "";
	}

	private void updateAttendLevel(ArrayList<AttendanceEventModel> attendLevelChanges,
			SalesForceAttendanceModel inputModel, Contact contactWithData, Student_Attendance__c newAttendRecord,
			AttendanceEventModel dbAttend) {

		// Failed to find attendance record in Tracker DB: this should not happen!
		if (dbAttend == null) {
			// Only report error if this was not because of a missing event name
			if (inputModel.getEventName() != null && !inputModel.getEventName().equals("")) {
				MySqlDbLogging.insertLogData(LogDataModel.MISSING_VISIT_ID_FOR_SF_IMPORT,
						new StudentNameModel(contactWithData.getFirstName(), contactWithData.getLastName(), false),
						Integer.parseInt(inputModel.getClientID()), ": Visit ID " + inputModel.getVisitID() + " for "
								+ inputModel.getEventName() + " on " + inputModel.getServiceDate());
			}
			return;
		}

		// Insert current level into attendance for all class java, jslam, jlab
		if (inputModel.getEventType().startsWith("class j") && dbAttend.getState().equals("completed")) {
			// Only update when DB state just transitioned to complete!
			if (!dbAttend.getState().equals(dbAttend.getLastSFState())) {
				String repoLevel = getRepoLevelString(newAttendRecord);

				if (contactWithData.getHighest_Level__c() == null) {
					// If SF level is null, assume this is level 0
					newAttendRecord.setInternal_level__c("0");

					// Check if DB attendance is also 0
					if (!dbAttend.getClassLevel().equals("0"))
						MySqlDbLogging.insertLogData(LogDataModel.CLASS_LEVEL_MISMATCH,
								new StudentNameModel(contactWithData.getFirstName(), contactWithData.getLastName(),
										false),
								Integer.parseInt(inputModel.getClientID()),
								" (SF) for " + inputModel.getEventName() + " on " + dbAttend.getServiceDateString()
										+ ", Pike13 Level = " + dbAttend.getClassLevel() + ", SF Grad = - (set to 0)");

				} else if (!dbAttend.getClassLevel().equals("")) {
					// Normal case is where class level is "gradlevel + 1"
					int highestLevel = (int) ((double) contactWithData.getHighest_Level__c());
					int delta = (dbAttend.getClassLevel().charAt(0) - '0') - highestLevel;
					if (delta != 1 && repoLevel != null) {
						// Classes taken out-of-order; use repo if possible
						newAttendRecord.setInternal_level__c(repoLevel);
					} else if (dbAttend.getEventName().startsWith("EL")) {
						// Ignore elective classes
						newAttendRecord.setInternal_level__c("");
					} else if (highestLevel < 8) {
						newAttendRecord.setInternal_level__c(((Integer) (highestLevel + 1)).toString());
					} else {
						// This will show up as an error in salesforce report
						newAttendRecord.setInternal_level__c("");
					}

					if (!newAttendRecord.getInternal_level__c().equals("") 
							&& !newAttendRecord.getInternal_level__c().equals(dbAttend.getClassLevel()))
						// Mismatched levels
						MySqlDbLogging.insertLogData(LogDataModel.CLASS_LEVEL_MISMATCH,
								new StudentNameModel(
										contactWithData.getFirstName(), contactWithData.getLastName(), false),
								Integer.parseInt(inputModel.getClientID()),
								" (SF) for " + inputModel.getEventName() + " on " + dbAttend.getServiceDateString()
										+ ", Pike13 Level = " + dbAttend.getClassLevel() + ", SF Grad = " + highestLevel
										+ " (set to " + newAttendRecord.getInternal_level__c() + ")");
				}
				// Attendance just became completed, so update Last SF State
				attendLevelChanges.add(dbAttend);

				// Check that github repo name matches new attendance level
				if (repoLevel != null && !repoLevel.equals(newAttendRecord.getInternal_level__c())
						 && !dbAttend.getEventName().startsWith("EL")) {
					// Github repo level does not match internal level
					MySqlDbLogging.insertLogData(LogDataModel.CLASS_LEVEL_MISMATCH,
							new StudentNameModel(contactWithData.getFirstName(), contactWithData.getLastName(), false),
							dbAttend.getClientID(),
							" (SF) for repo '" + newAttendRecord.getRepo_Name__c() + "' on "
									+ dbAttend.getServiceDateString() + ", expected class level "
									+ dbAttend.getClassLevel() + " (set to " + newAttendRecord.getInternal_level__c()
									+ ")");
				}
			}
		}
	}

	private String getRepoLevelString(Student_Attendance__c newAttendRecord) {
		// If repo is null, return null
		if (newAttendRecord.getRepo_Name__c() == null)
			return null;

		// Check if github repo matches classroom naming for level
		String levelString = null;
		String repoName = newAttendRecord.getRepo_Name__c().toLowerCase();
		if ((repoName.startsWith("level-") || repoName.startsWith("level_")) && repoName.length() > 7 
				&& (repoName.substring(7).startsWith("-module") || repoName.substring(7).startsWith("-coding") 
						|| repoName.substring(7).startsWith("-practice") || repoName.substring(7).startsWith("-checkpoint")))
			levelString = repoName.substring(6, 7);
		
		else if (repoName.startsWith("level") && repoName.length() > 6 
				&& (repoName.substring(6).startsWith("-module") || repoName.substring(6).startsWith("-exam") 
						|| repoName.substring(6).startsWith("-coding") || repoName.substring(6).startsWith("-checkpoint")))
			levelString = repoName.substring(5, 6);
		
		else if (repoName.startsWith("old-level") && repoName.length() > 10
				&& repoName.substring(10).startsWith("-module"))
			levelString = repoName.substring(9, 10);
		
		else if (repoName.startsWith("level5-0") && repoName.length() > 9 
				&& repoName.charAt(9) == '-')
			levelString = "5";
		
		else if (repoName.startsWith("processingsnake-") || repoName.startsWith("league-level2-game") ||
				 repoName.startsWith("league-invaders-"))
			levelString = "2";
		
		else if (repoName.startsWith("lvl6-project"))
			levelString = "6";

		// Found github classroom, now check if starts with digit
		if (levelString != null && levelString.compareTo("0") >= 0 && levelString.compareTo("5") <= 0)
			return levelString;
		else
			return null;
	}

	private boolean checkForVisitIdNull(SalesForceAttendanceModel inputModel) {
		if (inputModel.getVisitID() == null || inputModel.getVisitID().equals("null")
				|| inputModel.getVisitID().equals("")) {
			// No Visit ID. Ignore this record if status not these accepted values.
			// This will occur for wait-listed students (states waiting, expired, other?).
			if (!inputModel.getStatus().equals("completed") && !inputModel.getStatus().equals("late_canceled")
					&& !inputModel.getStatus().equals("registered") && !inputModel.getStatus().equals("noshowed")) {
				return true;
			}
		}
		return false;
	}

	public void removeExtraAttendanceRecords(ArrayList<SalesForceAttendanceModel> pike13Attendance, String startDate,
			String endDate, ArrayList<StudentImportModel> studentList) {

		boolean done = false;
		QueryResult queryResult;
		ArrayList<String> deleteList = new ArrayList<String>();

		if (attendanceUpsertError) {
			MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_CANCELED_ATTEND_CLEANUP,
					new StudentNameModel("", "", false), 0,
					", 0 records deleted: aborted due to attendance upsert error(s)");
			return;
		}

		try {
			queryResult = connection
					.query("SELECT Id, Visit_Id__c, Service_Date__c, Front_Desk_ID__c, Event_Name__c, CreatedBy.Name, Status__c "
							+ "FROM Student_Attendance__c WHERE CreatedBy.Name = 'League Bot' AND Visit_Id__c != NULL "
							+ "AND Service_Date__c >= " + startDate + " AND Service_Date__c <= " + endDate + " AND Status__c != 'Completed' "
							+ "ORDER BY Visit_Id__c ASC");
			System.out.println(queryResult.getSize() + " future Salesforce attendance records for League Bot");

			if (queryResult.getSize() > 0) {
				while (!done) {
					SObject[] records = queryResult.getRecords();
					for (int i = 0; i < records.length; i++) {
						// Check whether attendance record exists in Pike13
						Student_Attendance__c a = (Student_Attendance__c) records[i];
						if (!ListUtilities.findVisitIdInList(a.getVisit_Id__c(), pike13Attendance)) {
							// Record not found, so add to deletion list
							deleteList.add(a.getId());
						}
					}
					if (queryResult.isDone())
						done = true;
					else
						queryResult = connection.queryMore(queryResult.getQueryLocator());
				}

				// Delete cancelled (future) attendance records
				System.out.println("Attendance record delete from " + startDate + " to " + endDate + ", count: " + deleteList.size());
				if (deleteList.size() > 0)
					deleteAttendanceRecords((String[]) deleteList.toArray(new String[0]));
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void updateGraduates(ArrayList<GraduationModel> gradStudents, ArrayList<Contact> sfContacts,
			ArrayList<Contact_Diary__c> sfDiary) {
		ArrayList<Contact_Diary__c> recordList = new ArrayList<Contact_Diary__c>();
		ArrayList<Contact> contactList = new ArrayList<Contact>();

		try {
			for (int i = 0; i < gradStudents.size(); i++) {
				// Add each graduate student to SalesForce list
				GraduationModel student = gradStudents.get(i);
				if (student.isSfUpdated())
					continue;

				// Check if student contact is in SalesForce
				String clientIdString = ((Integer) student.getClientID()).toString();
				Contact c = ListUtilities.findClientIDInList(LogDataModel.MISSING_SF_CONTACT_FOR_GRADUATION,
						clientIdString, student.getStudentName(), "", sfContacts);
				if (c == null)
					continue;

				// Create contact and add to list
				String clientLevelKey = clientIdString + student.getGradLevelString();
				Contact_Diary__c diaryEntry = new Contact_Diary__c();
				diaryEntry.setStudent_Contact__r(c);
				String id = ListUtilities.findDiaryIdInList(clientLevelKey, sfDiary);
				if (id != null)
					diaryEntry.setId(id);
				diaryEntry.setPike_13_ID_Level__c (clientLevelKey);
				
				if (student.getGradLevel() == GraduationModel.AP_COMPA_EXAM) {
					diaryEntry.setDiary_Type__c("Test");
					diaryEntry.setDescription__c("AP CompA Exam");
					Contact gradContact = new Contact();
					if (student.getScore().equals("3") || student.getScore().equals("4") || student.getScore().equals("5")) {
						gradContact.setId(c.getId());
						gradContact.setFront_Desk_Id__c(c.getFront_Desk_Id__c());
						gradContact.setPassed_AP_Comp_A__c(true);
						contactList.add(gradContact);
					}
					System.out.println("AP CompA grad: " + student.getStudentName() + ", score " + student.getScore() 
						+ ", passed " + gradContact.getPassed_AP_Comp_A__c());
				}
				else if (student.getGradLevel() == GraduationModel.AP_PRINC_EXAM) {
					diaryEntry.setDiary_Type__c("Test");
					diaryEntry.setDescription__c("AP Principles Exam");
					Contact gradContact = new Contact();
					if (student.getScore().equals("3") || student.getScore().equals("4") || student.getScore().equals("5")) {
						gradContact.setId(c.getId());
						gradContact.setFront_Desk_Id__c(c.getFront_Desk_Id__c());
						gradContact.setPassed_Ap_Comp_Priciples__c(true);
						contactList.add(gradContact);
					}
					System.out.println("AP Principles grad: " + student.getStudentName() + ", score " + student.getScore() 
						+ ", passed " + gradContact.getPassed_Ap_Comp_Priciples__c());
				} 
				else if (student.getGradLevel() == GraduationModel.ORACLE_EXAM) {
					diaryEntry.setDiary_Type__c("Test");
					diaryEntry.setDescription__c("Oracle Exam");
					Contact gradContact = new Contact();
					if (student.getScore().toLowerCase().contains("pass") 
							|| isPassingGrade(student.getScore(), PASSING_GRADE_FOR_ORACLE)) {
						gradContact.setId(c.getId());
						gradContact.setFront_Desk_Id__c(c.getFront_Desk_Id__c());
						gradContact.setPassed_Oracle__c(true);
						contactList.add(gradContact);
					}
					System.out.println("Oracle grad: " + student.getStudentName() + ", score " + student.getScore() 
						+ ", passed " + gradContact.getPassed_Oracle__c());
					
				} else {  // Class levels 0 - 8
					diaryEntry.setDiary_Type__c("Level");
					if (student.getGradLevel() == 8)
						diaryEntry.setDescription__c("Level 8 Oracle Prep");
					else
						diaryEntry.setDescription__c("Level " + student.getGradLevel());
					diaryEntry.setLevel__c (student.getGradLevelString());
				}

				Calendar endDate = convertDateStringToCalendar(student.getEndDate());
				diaryEntry.setEnd_Date__c(endDate);
				diaryEntry.setDiary_Date__c(endDate);
				if (student.getStartDate() != null && !student.getStartDate().equals(""))
					diaryEntry.setStart_Date__c(convertDateStringToCalendar(student.getStartDate()));

				if (student.isPromoted()) {
					diaryEntry.setPromoted__c(true); // Did not pass test
					diaryEntry.setSkipped_Level__c(false);
				}
				else if (student.isSkipLevel()) {
					diaryEntry.setSkipped_Level__c(true);
					diaryEntry.setPromoted__c(false);
					diaryEntry.setStart_Date__c(endDate);
				} 
				else if (student.getScore() != null && !student.getScore().equals("")) {
					diaryEntry.setScore__c(student.getScore());
					if (student.getGradLevel() <= 8 && isPassingGrade(student.getScore(), PASSING_GRADE_FOR_CLASSES))
						diaryEntry.setPromoted__c(false);
				}

				int numClasses = 0;
				if (student.getGradLevel() <= 8) {   // ignore AP and Oracle
					numClasses = getNumClassesByLevel(student.getClientID(), student.getGradLevelString());
					diaryEntry.setNum_Classes__c((double) numClasses);
				}

				recordList.add(diaryEntry);
				MySqlDbLogging.insertLogData(LogDataModel.STUDENT_GRADUATION,
						new StudentNameModel(student.getStudentName(), "", false), student.getClientID(), " "
								+ diaryEntry.getDescription__c() + " on " + student.getEndDate() + ", # Classes " + numClasses);
			}

			upsertDiaryRecordList(recordList);
			if (contactList.size() > 0)
				upsertContactRecordList(contactList, "Oracle-AP Exam");

		} catch (Exception e) {
			if (e.getMessage() == null || e.getMessage().equals("null")) {
				MySqlDbLogging.insertLogData(LogDataModel.SF_DIARY_IMPORT_ERROR, new StudentNameModel("", "", false), 0,
						"");
				e.printStackTrace();
			} else
				MySqlDbLogging.insertLogData(LogDataModel.SF_DIARY_IMPORT_ERROR, new StudentNameModel("", "", false), 0,
						": " + e.getMessage());
		}
	}

	private boolean isPassingGrade(String score, double passingGrade) {
		try {
			// Try converting to double
			if (Double.parseDouble(score) >= passingGrade)
				return true;

		} catch (NumberFormatException e) {

		}

		return false;
	}

	public void updateStaffMembers(ArrayList<StaffMemberModel> pike13StaffMembers, ArrayList<Contact> sfContacts,
			ArrayList<Account> sfAccounts) {
		ArrayList<Contact> recordList = new ArrayList<Contact>();

		try {
			for (int i = 0; i < pike13StaffMembers.size(); i++) {
				// Add each Pike13 staff record to SalesForce list
				StaffMemberModel staff = pike13StaffMembers.get(i);

				String firstName = staff.getFirstName();
				String clientID = staff.getClientID();
				String accountID, contactType = "Adult";

				if (firstName.startsWith("TA-") || firstName.toLowerCase().startsWith("trainee-")) {
					// Remove TA- or Trainee- from front of string
					firstName = firstName.substring(firstName.indexOf("-") + 1);

					// TA's must have valid SFClientID that is different from ClientID
					if (clientID.equals(staff.getSfClientID())) {
						MySqlDbLogging.insertLogData(LogDataModel.MISSING_SF_CLIENT_ID_FOR_TA,
								new StudentNameModel(firstName, staff.getLastName(), false), Integer.parseInt(clientID),
								" for " + staff.getFullName());
						continue;
					}

					// TA's use SF Client ID
					clientID = staff.getSfClientID();
					contactType = "Student";
					if (staff.getFirstName().toLowerCase().startsWith("trainee-"))
						System.out.println("Trainee: " + firstName + ", " + staff.getFullName());
				}

				// Get account ID, create account if needed
				Contact c = ListUtilities.findClientIDInList(-1, clientID, staff.getFullName(), "", sfContacts);
				if (c == null) {
					accountID = getStaffAccountID(staff, sfAccounts);
					if (accountID == null)
						continue;
				} else
					accountID = c.getAccountId();

				// Add to list
				recordList.add(createStaffRecord(staff, firstName, clientID, accountID, contactType));
			}

			upsertContactRecordList(recordList, "staff");

		} catch (Exception e) {
			if (e.getMessage() == null || e.getMessage().equals("null")) {
				MySqlDbLogging.insertLogData(LogDataModel.SF_CLIENT_IMPORT_ERROR, new StudentNameModel("", "", false),
						0, "");
				e.printStackTrace();
			} else
				MySqlDbLogging.insertLogData(LogDataModel.SF_CLIENT_IMPORT_ERROR, new StudentNameModel("", "", false),
						0, ": " + e.getMessage());
		}
	}

	public void updateStaffHours(ArrayList<StaffMemberModel> pike13StaffMembers,
			ArrayList<SalesForceStaffHoursModel> pike13StaffHours, ArrayList<Contact> contacts) {
		ArrayList<Staff_Hours__c> recordList = new ArrayList<Staff_Hours__c>();
		ArrayList<Staff_Hours__c> adminHoursList = new ArrayList<Staff_Hours__c>();

		// Get staff admin contact record for updating summary hours
		Contact adminContact = ListUtilities.findClientIDInList(LogDataModel.MISSING_SALES_FORCE_STAFF_MEMBER,
				ADMIN_STAFF_CLIENT_ID, ADMIN_STAFF_NAME, "", contacts);

		System.out.println(pike13StaffHours.size() + " Staff Hour records from Pike13");

		try {
			for (int i = 0; i < pike13StaffHours.size(); i++) {
				// Add each Pike13 staff hours record to SalesForce list
				SalesForceStaffHoursModel inputModel = pike13StaffHours.get(i);
				if (inputModel.getFullName().toLowerCase().startsWith("intro to java")
						|| inputModel.getFullName().toLowerCase().startsWith("coding camp")
						|| inputModel.getFullName().toLowerCase().startsWith("league workshop")
						|| inputModel.getFullName().toLowerCase().startsWith("make-up")
						|| inputModel.getFullName().startsWith("Summer Programs")
						|| inputModel.getFullName().startsWith("Need Assist")
						|| inputModel.getFullName().startsWith("Needs Assist")
						|| inputModel.getFullName().toLowerCase().startsWith("needs teacher")
						|| inputModel.getFullName().toLowerCase().startsWith("sub teacher")
						|| inputModel.getFullName().toLowerCase().startsWith("league admin")
						|| inputModel.getFullName().startsWith("Accepting Students"))
					continue;

				StaffMemberModel staff = ListUtilities.findStaffIDInList(LogDataModel.MISSING_PIKE13_STAFF_MEMBER,
						inputModel.getClientID(), inputModel.getFullName(), inputModel.getServiceDate(),
						inputModel.getEventName(), pike13StaffMembers);
				if (staff == null)
					continue;

				Contact c = ListUtilities.findClientIDInList(LogDataModel.MISSING_SALES_FORCE_STAFF_MEMBER,
						staff.getSfClientID(), inputModel.getFullName(), "", contacts);
				if (c == null)
					continue;

				Staff_Hours__c h = createStaffHoursRecord(inputModel, c);
				updateAdminHours(inputModel, staff.getCategory(), adminContact, adminHoursList);

				recordList.add(h);
			}

			if (adminHoursList.size() > 0) {
				System.out.println(adminHoursList.size() + " Admin Staff Hour records");
				recordList.addAll(adminHoursList);
			}

			// Copy up to 200 records to array at a time (max allowed)
			Staff_Hours__c[] recordArray;
			int numRecords = recordList.size();
			int remainingRecords = numRecords;
			int arrayIdx = 0;

			if (numRecords > MAX_NUM_UPSERT_RECORDS)
				recordArray = new Staff_Hours__c[MAX_NUM_UPSERT_RECORDS];
			else
				recordArray = new Staff_Hours__c[numRecords];

			for (int i = 0; i < numRecords; i++) {
				recordArray[arrayIdx] = recordList.get(i);

				arrayIdx++;
				if (arrayIdx == MAX_NUM_UPSERT_RECORDS) {
					upsertStaffHoursRecords(recordArray);
					remainingRecords -= MAX_NUM_UPSERT_RECORDS;
					arrayIdx = 0;

					if (remainingRecords > MAX_NUM_UPSERT_RECORDS)
						recordArray = new Staff_Hours__c[MAX_NUM_UPSERT_RECORDS];
					else if (remainingRecords > 0)
						recordArray = new Staff_Hours__c[remainingRecords];
				}
			}

			// Update remaining records in Salesforce.com
			if (arrayIdx > 0)
				upsertStaffHoursRecords(recordArray);

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private Staff_Hours__c createStaffHoursRecord(SalesForceStaffHoursModel inputModel, Contact c) {
		Staff_Hours__c h = new Staff_Hours__c();
		h.setStaff_Name__r(c);
		h.setEvent_Name__c(inputModel.getEventName());
		h.setService__c(inputModel.getServiceName());
		h.setSchedule_ID__c(inputModel.getScheduleID());
		h.setSchedule_client_ID__c(inputModel.getScheduleID() + inputModel.getClientID());
		h.setLocation_Full__c(inputModel.getLocation());
		h.setHours__c(inputModel.getHours());
		h.setPresent__c(inputModel.getCompleted());
		h.setAbsent__c(inputModel.getNoShow());
		h.setExcused__c(inputModel.getLateCanceled());
		if (inputModel.getServiceCategory() != null) {
			if (inputModel.getServiceCategory().length() > 6) {
				h.setEvent_type__c(inputModel.getServiceCategory().substring(0, 5));
				h.setEvent_type_sub__c(inputModel.getServiceCategory().substring(6));
			} else {
				h.setEvent_type__c(inputModel.getServiceCategory());
				h.setEvent_type_sub__c("");
			}
		}

		h.setService_Date__c(convertDateStringToCalendar(inputModel.getServiceDate()));
		h.setService_Time__c(inputModel.getServiceTime());

		return h;
	}

	private void updateAdminHours(SalesForceStaffHoursModel inputModel, String staffCategory, Contact adminContact,
			ArrayList<Staff_Hours__c> adminHoursList) {

		if (adminContact == null)
			return;

		Staff_Hours__c adminHours = null;
		for (Staff_Hours__c a : adminHoursList) {
			if (a.getSchedule_ID__c().equals(inputModel.getScheduleID())) {
				adminHours = a;
				break;
			}
		}

		// This schedule ID not yet in the list, so create record and add
		if (adminHours == null) {
			adminHours = createAdminStaffHoursRecord(adminContact, inputModel);
			if (adminHours == null)
				return;

			adminHoursList.add(adminHours);
		}

		// Update summary counts
		if (staffCategory.equals("Student TA"))
			adminHours.setStype_ta_sum__c(adminHours.getStype_ta_sum__c() + 1);
		else if (staffCategory.equals("Teaching Staff"))
			adminHours.setStype_staff_sum__c(adminHours.getStype_staff_sum__c() + 1);
		else if (staffCategory.equals("Vol Teacher"))
			adminHours.setStype_vol_sum__c(adminHours.getStype_vol_sum__c() + 1);
	}

	private Staff_Hours__c createAdminStaffHoursRecord(Contact c, SalesForceStaffHoursModel inputModel) {

		// Create staff hours model from input
		SalesForceStaffHoursModel model = new SalesForceStaffHoursModel(ADMIN_STAFF_CLIENT_ID, ADMIN_STAFF_NAME,
				inputModel.getServiceName(), inputModel.getServiceDate(), inputModel.getServiceTime(),
				inputModel.getHours(), inputModel.getLocation(), inputModel.getCompleted(), inputModel.getNoShow(),
				inputModel.getLateCanceled(), inputModel.getEventName(), inputModel.getScheduleID(),
				inputModel.getServiceCategory());

		// Create staff hours record for Staff Admin
		Staff_Hours__c h = createStaffHoursRecord(model, c);

		// Initialize SType
		h.setStype_ta_sum__c(0.0);
		h.setStype_staff_sum__c(0.0);
		h.setStype_vol_sum__c(0.0);

		return h;
	}

	private void upsertClientRecords(Contact[] records) {
		UpsertResult[] upsertResults = null;

		try {
			// Update the records in Salesforce.com
			upsertResults = connection.upsert("Front_Desk_ID__c", records);

		} catch (ConnectionException e) {
			if (e.getMessage() == null || e.getMessage().equals("null")) {
				MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_UPSERT_CLIENTS_ERROR,
						new StudentNameModel("", "", false), 0, "");
				e.printStackTrace();
			} else
				MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_UPSERT_CLIENTS_ERROR,
						new StudentNameModel("", "", false), 0, ": " + e.getMessage());
			return;
		}

		// check the returned results for any errors
		for (int i = 0; i < upsertResults.length; i++) {
			if (!upsertResults[i].isSuccess()) {
				// If client ID is numeric, then use this in error log
				int clientID = 0;
				if (records[i].getFront_Desk_Id__c().matches("\\d+"))
					clientID = Integer.parseInt(records[i].getFront_Desk_Id__c());

				Error[] errors = upsertResults[i].getErrors();
				for (int j = 0; j < errors.length; j++) {
					MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_UPSERT_CLIENTS_ERROR,
							new StudentNameModel(records[i].getFirstName(), records[i].getLastName(), false), clientID,
							": " + errors[j].getMessage());
				}
			}
		}
	}

	private void upsertAccountRecords(Account[] records) {
		UpsertResult[] upsertResults = null;

		try {
			// Update the Account records in Salesforce.com
			upsertResults = connection.upsert("Id", records);

		} catch (ConnectionException e) {
			if (e.getMessage() == null || e.getMessage().equals("null")) {
				MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_UPSERT_ACCOUNT_ERROR,
						new StudentNameModel("", "", false), 0, "");
				e.printStackTrace();
			} else
				MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_UPSERT_ACCOUNT_ERROR,
						new StudentNameModel("", "", false), 0, ": " + e.getMessage());
			return;
		}

		// check the returned results for any errors
		for (int i = 0; i < upsertResults.length; i++) {
			if (!upsertResults[i].isSuccess()) {
				Error[] errors = upsertResults[i].getErrors();
				for (int j = 0; j < errors.length; j++) {
					MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_UPSERT_ACCOUNT_ERROR,
							new StudentNameModel(records[i].getName(), "", false), 0,
							": " + errors[j].getMessage());
				}
			}
		}
	}

	private void upsertAttendanceRecords(Student_Attendance__c[] records) {
		UpsertResult[] upsertResults = null;

		try {
			// Update the records in Salesforce.com
			upsertResults = connection.upsert("Visit_Id__c", records);

		} catch (ConnectionException e) {
			attendanceUpsertError = true;
			if (e.getMessage() == null || e.getMessage().equals("null")) {
				MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_UPSERT_ATTENDANCE_ERROR,
						new StudentNameModel("", "", false), 0, "");
				e.printStackTrace();
			} else
				MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_UPSERT_ATTENDANCE_ERROR,
						new StudentNameModel("", "", false), 0, ": " + e.getMessage());
			return;
		}

		// check the returned results for any errors
		for (int i = 0; i < upsertResults.length; i++) {
			if (!upsertResults[i].isSuccess()) {
				// If client ID is numeric, then use this in error log
				Contact c = records[i].getContact__r();
				int clientID = 0;
				if (c != null && c.getFront_Desk_Id__c() != null && c.getFront_Desk_Id__c().matches("\\d+"))
					clientID = Integer.parseInt(c.getFront_Desk_Id__c());

				attendanceUpsertError = true;
				Error[] errors = upsertResults[i].getErrors();
				for (int j = 0; j < errors.length; j++) {
					MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_UPSERT_ATTENDANCE_ERROR,
							new StudentNameModel("", "", false), clientID, ": " + errors[j].getMessage());
				}
			}
		}
	}

	private void deleteAttendanceRecords(String[] records) {
		DeleteResult[] deleteResults = null;

		try {
			// Update the records in Salesforce.com
			deleteResults = connection.delete(records);

		} catch (ConnectionException e) {
			if (e.getMessage() == null || e.getMessage().equals("null")) {
				MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_DELETE_ATTENDANCE_ERROR,
						new StudentNameModel("", "", false), 0, "");
				e.printStackTrace();
			} else
				MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_DELETE_ATTENDANCE_ERROR,
						new StudentNameModel("", "", false), 0, ": " + e.getMessage());
			return;
		}

		// check the returned results for any errors
		for (int i = 0; i < deleteResults.length; i++) {
			if (!deleteResults[i].isSuccess()) {
				Error[] errors = deleteResults[i].getErrors();
				for (int j = 0; j < errors.length; j++) {
					MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_DELETE_ATTENDANCE_ERROR,
							new StudentNameModel("", "", false), 0, ": " + errors[j].getMessage());
				}
			}
		}
	}

	private void upsertStaffHoursRecords(Staff_Hours__c[] records) {
		UpsertResult[] upsertResults = null;

		try {
			// Update the records in Salesforce.com
			upsertResults = connection.upsert("schedule_client_ID__c", records);

		} catch (ConnectionException e) {
			if (e.getMessage() == null || e.getMessage().equals("null")) {
				MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_UPSERT_STAFF_HOURS_ERROR,
						new StudentNameModel("", "", false), 0, "");
				e.printStackTrace();
			} else
				MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_UPSERT_STAFF_HOURS_ERROR,
						new StudentNameModel("", "", false), 0, ": " + e.getMessage());
			return;
		}

		// check the returned results for any errors
		for (int i = 0; i < upsertResults.length; i++) {
			if (!upsertResults[i].isSuccess()) {
				Error[] errors = upsertResults[i].getErrors();
				for (int j = 0; j < errors.length; j++) {
					MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_UPSERT_STAFF_HOURS_ERROR,
							new StudentNameModel("", "", false),
							Integer.parseInt(records[i].getStaff_Name__r().getFront_Desk_Id__c()),
							" (" + records[i].getSchedule_client_ID__c() + "): " + errors[j].getMessage());
				}
			}
		}
	}

	private void upsertContactRecordList(ArrayList<Contact> recordList, String recordType) {
		// Copy up to 200 records to array at a time (max allowed)
		Contact[] recordArray;
		int numRecords = recordList.size();
		int remainingRecords = numRecords;
		int arrayIdx = 0;

		System.out.println(numRecords + " Pike13 " + recordType + " records");
		if (numRecords > MAX_NUM_UPSERT_RECORDS)
			recordArray = new Contact[MAX_NUM_UPSERT_RECORDS];
		else
			recordArray = new Contact[numRecords];

		for (int i = 0; i < numRecords; i++) {
			recordArray[arrayIdx] = recordList.get(i);

			arrayIdx++;
			if (arrayIdx == MAX_NUM_UPSERT_RECORDS) {
				upsertClientRecords(recordArray);
				remainingRecords -= MAX_NUM_UPSERT_RECORDS;
				arrayIdx = 0;

				if (remainingRecords > MAX_NUM_UPSERT_RECORDS)
					recordArray = new Contact[MAX_NUM_UPSERT_RECORDS];
				else if (remainingRecords > 0)
					recordArray = new Contact[remainingRecords];
			}
		}

		// Update remaining records in Salesforce.com
		if (arrayIdx > 0)
			upsertClientRecords(recordArray);
	}

	private void upsertAccountRecordList(ArrayList<Account> recordList, String recordType) {
		// Copy up to 200 records to array at a time (max allowed)
		Account[] recordArray;
		int numRecords = recordList.size();
		int remainingRecords = numRecords;
		int arrayIdx = 0;

		System.out.println(numRecords + " Pike13 " + recordType + " records");
		if (numRecords > MAX_NUM_UPSERT_RECORDS)
			recordArray = new Account[MAX_NUM_UPSERT_RECORDS];
		else
			recordArray = new Account[numRecords];

		for (int i = 0; i < numRecords; i++) {
			recordArray[arrayIdx] = recordList.get(i);

			arrayIdx++;
			if (arrayIdx == MAX_NUM_UPSERT_RECORDS) {
				upsertAccountRecords(recordArray);
				remainingRecords -= MAX_NUM_UPSERT_RECORDS;
				arrayIdx = 0;

				if (remainingRecords > MAX_NUM_UPSERT_RECORDS)
					recordArray = new Account[MAX_NUM_UPSERT_RECORDS];
				else if (remainingRecords > 0)
					recordArray = new Account[remainingRecords];
			}
		}

		// Update remaining records in Salesforce.com
		if (arrayIdx > 0)
			upsertAccountRecords(recordArray);
	}

	private void upsertDiaryRecordList(ArrayList<Contact_Diary__c> recordList) {
		Contact_Diary__c[] recordArray = new Contact_Diary__c[recordList.size()];
		UpsertResult[] upsertResults = null;

		// Copy contact diary to record array
		for (int i = 0; i < recordList.size(); i++) {
			recordArray[i] = recordList.get(i);
		}
		System.out.println(recordList.size() + " Graduation records");

		try {
			// Update the records in Salesforce.com
			upsertResults = connection.upsert("Id", recordArray);

		} catch (ConnectionException e) {
			if (e.getMessage() == null || e.getMessage().equals("null")) {
				MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_UPSERT_DIARY_ERROR,
						new StudentNameModel("", "", false), 0, "");
				e.printStackTrace();

			} else
				MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_UPSERT_DIARY_ERROR,
						new StudentNameModel("", "", false), 0, ": " + e.getMessage());
			return;
		}

		// check the returned results for any errors
		for (int i = 0; i < upsertResults.length; i++) {
			int clientID = 0;
			String clientIdString = recordArray[i].getStudent_Contact__r().getFront_Desk_Id__c();

			// If client ID is numeric, then use this in error log
			if (clientIdString.matches("\\d+"))
				clientID = Integer.parseInt(clientIdString);

			if (upsertResults[i].isSuccess()) {
				if (clientID > 0) {
					// Graduation diary entry successfully added to SF, so update SQL DB flag
					String graduateName = recordArray[i].getStudent_Contact__r().getFull_Name__c();
					dbImports.updateGradInSfField(clientID, graduateName, recordArray[i].getPike_13_ID_Level__c(), true);
				}

			} else {
				Error[] errors = upsertResults[i].getErrors();
				for (int j = 0; j < errors.length; j++) {
					MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_UPSERT_DIARY_ERROR,
							new StudentNameModel("", "", false), clientID, ": " + errors[j].getMessage());
				}
			}
		}
	}

	private void updateWorkshopGrad(ArrayList<Contact> workShopGrads, SalesForceAttendanceModel inputModel) {
		// Check for null lists
		if (inputModel.getEventName() == null || inputModel.getStatus() == null || inputModel.getServiceDate() == null)
			return;

		// If event is Intro to Java Workshop (also known as "Coding Camp") and is completed, add to grad date list
		if ((inputModel.getEventName().toLowerCase().contains("intro to java") || inputModel.getEventName().toLowerCase().contains("coding camp"))
				&& inputModel.getStatus().equals("completed")
				&& inputModel.getServiceDate().compareTo(getDateInPastByWeeks(WEEKS_TO_UPDATE_WORKSHOP_GRADS)) > 0) {
			Contact gradContact = new Contact();
			Calendar newGradCal = convertDateStringToCalendar(inputModel.getServiceDate());
			gradContact.setFront_Desk_Id__c(inputModel.getClientID());
			gradContact.setWorkshop_Grad_Date__c(newGradCal);

			// Add record; if already in grad list, remove and replace with later grad date
			Contact dupContact = ListUtilities.findClientIDInList(-1, inputModel.getClientID(),
					inputModel.getFullName(), "", workShopGrads);

			if (dupContact == null) {
				// Not already in list, so add
				workShopGrads.add(gradContact);

			} else if (dupContact.getWorkshop_Grad_Date__c().compareTo(newGradCal) < 0) {
				// This client is already in list and older date, so update wshop grad date
				dupContact.setWorkshop_Grad_Date__c(newGradCal);
			}
		}
	}

	private void upsertWorkshopByTeacherRecordList(ArrayList<Workshop_by_Teacher__c> recordList) {
		// Copy up to 200 records to array at a time (max allowed)
		Workshop_by_Teacher__c[] recordArray;
		int numRecords = recordList.size();
		int remainingRecords = numRecords;
		int arrayIdx = 0;

		System.out.println(numRecords + " Workshop by Teacher records");
		if (numRecords > MAX_NUM_UPSERT_RECORDS)
			recordArray = new Workshop_by_Teacher__c[MAX_NUM_UPSERT_RECORDS];
		else
			recordArray = new Workshop_by_Teacher__c[numRecords];

		for (int i = 0; i < numRecords; i++) {
			recordArray[arrayIdx] = recordList.get(i);

			arrayIdx++;
			if (arrayIdx == MAX_NUM_UPSERT_RECORDS) {
				upsertWorkshopByTeacherRecords(recordArray);
				remainingRecords -= MAX_NUM_UPSERT_RECORDS;
				arrayIdx = 0;

				if (remainingRecords > MAX_NUM_UPSERT_RECORDS)
					recordArray = new Workshop_by_Teacher__c[MAX_NUM_UPSERT_RECORDS];
				else if (remainingRecords > 0)
					recordArray = new Workshop_by_Teacher__c[remainingRecords];
			}
		}

		// Update remaining records in Salesforce.com
		if (arrayIdx > 0)
			upsertWorkshopByTeacherRecords(recordArray);
	}
	
	private void upsertWorkshopByTeacherRecords(Workshop_by_Teacher__c[] records) {
		UpsertResult[] upsertResults = null;

		try {
			// Update the records in Salesforce.com
			upsertResults = connection.upsert("Unique_Id__c", records);

		} catch (ConnectionException e) {
			if (e.getMessage() == null || e.getMessage().equals("null")) {
				MySqlDbLogging.insertLogData(LogDataModel.SF_UPSERT_WSHOP_BY_TEACHER_ERROR,
						new StudentNameModel("", "", false), 0, "");
				e.printStackTrace();
			} else
				MySqlDbLogging.insertLogData(LogDataModel.SF_UPSERT_WSHOP_BY_TEACHER_ERROR,
						new StudentNameModel("", "", false), 0, ": " + e.getMessage());
			return;
		}

		// check the returned results for any errors
		for (int i = 0; i < upsertResults.length; i++) {
			if (!upsertResults[i].isSuccess()) {
				// If client ID is numeric, then use this in error log
				int clientID = 0;
				if (records[i].getWShop_Contact__r() != null && records[i].getWShop_Contact__r().getFront_Desk_Id__c().matches("\\d+"))
					clientID = Integer.parseInt(records[i].getWShop_Contact__r().getFront_Desk_Id__c());

				Error[] errors = upsertResults[i].getErrors();
				for (int j = 0; j < errors.length; j++) {
					MySqlDbLogging.insertLogData(LogDataModel.SF_UPSERT_WSHOP_BY_TEACHER_ERROR,
							new StudentNameModel("", "", false), clientID,
							": " + errors[j].getMessage());
				}
			}
		}
	}

	private Contact createContactRecord(boolean adult, StudentImportModel contact, Account account,
			ArrayList<StudentImportModel> pike13Students) {
		// Create contact and add fields
		Contact c = new Contact();

		c.setAccountId(account.getId());
		c.setFront_Desk_Id__c(String.valueOf(contact.getClientID()));
		c.setFirstName(camelCase(contact.getFirstName().trim()));
		c.setLastName(camelCase(contact.getLastName().trim()));
		if (adult) {
			c.setContact_Type__c("Adult");
			c.setRecordTypeId(RECORD_TYPE_ID_ADULT);
		} else {
			c.setContact_Type__c("Student");
			c.setRecordTypeId(RECORD_TYPE_ID_STUDENT);
			updateFamilyEmail(contact, c);
		}

		if (contact.getEmail() != null)
			c.setEmail(contact.getEmail());

		if (contact.getPhone1() != null) {
			if (contact.phone1_type.equals("Cell"))
				c.setMobilePhone (parsePhone(contact.getPhone1()));
			else if (contact.phone1_type.equals("Work"))
				c.setWork_Phone__c (parsePhone(contact.getPhone1()));
			else if (contact.phone1_type.equals("Home"))
				c.setHomePhone (parsePhone(contact.getPhone1()));
			else if (contact.phone1_type.equals("Other"))
				c.setOtherPhone (parsePhone(contact.getPhone1()));
			else if (contact.phone1_type.equals(""))
				// Historically, before this field existed, phone1 field was the MOBILE phone
				c.setMobilePhone (parsePhone(contact.getPhone1()));
			else  
				// This should never happen; phone type field is a drop-down menu in Pike13
				System.out.println(contact.getFullName() + (adult ? " (adult)" : " (student)") +": Unknown phone-1 type '" + contact.phone1_type + "'");
		}
		if (contact.getPhone2() != null) {
			if (contact.phone2_type.equals("Cell")) {
				// If 2 cell phones, set 2nd cell to "other"
				if (contact.phone1_type.equals("Cell"))
					c.setOtherPhone (parsePhone(contact.getPhone2()));
				else
					c.setMobilePhone (parsePhone(contact.getPhone2()));
			}
			else if (contact.phone2_type.equals("Work")) {
				// If 2 work phones, set 2nd phone to "other"
				if (contact.phone1_type.equals("Work"))
					c.setOtherPhone (parsePhone(contact.getPhone2()));
				else
					c.setWork_Phone__c (parsePhone(contact.getPhone2()));
			}
			else if (contact.phone2_type.equals("Home")) {
				// If 2 home phones, set 2nd phone to "other"
				if (contact.phone1_type.equals("Home"))
					c.setOtherPhone (parsePhone(contact.getPhone2()));
				else
					c.setHomePhone (parsePhone(contact.getPhone2()));
			}
			else if (contact.phone2_type.equals("Other")) {
				// If 2 'other' phones, just set this phone to 'cell'
				if (contact.phone1_type.equals("Other"))
					c.setMobilePhone (parsePhone(contact.getPhone2()));
				else
					c.setOtherPhone (parsePhone(contact.getPhone2()));
			}
			else if (contact.phone2_type.equals(""))
				// Historically, before this field existed, phone2 field was the HOME phone
				c.setHomePhone (parsePhone(contact.getPhone2()));
			else  
				// This should never happen; phone type field is a drop-down menu in Pike13
				System.out.println(contact.getFullName() + (adult ? " (adult)" : " (student)") +": Unknown phone-2 type '" + contact.phone2_type + "'");
		}
		
		if (contact.getAddress() != null) {
			c.setFull_Address__c(contact.getAddress());
			Address addr = parseAddress(contact.getAddress());
			if (addr != null) {
				c.setMailingStreet(addr.getStreet());
				c.setMailingCity(addr.getCity());
				c.setMailingState("California");
				c.setMailingCountry("United States");
				c.setMailingPostalCode(addr.getPostalCode());
			}
		}
		c.setPast_Events__c((double) contact.getCompletedVisits());
		c.setFuture_Events__c((double) contact.getFutureVisits());
		// c.setSigned_Waiver__c(contact.isSignedWaiver());
		c.setMembership__c(contact.getMembership());
		if (contact.getPassOnFile() != null && !contact.getPassOnFile().equals("")) {
			if (contact.getPassOnFile().length() > MAX_SALESFORCE_FIELD_LENGTH)
				c.setPlan__c(contact.getPassOnFile().substring(0, MAX_SALESFORCE_FIELD_LENGTH));
			else
				c.setPlan__c(contact.getPassOnFile());
		}
		if (contact.getHomeLocAsString() != null && !adult)
			c.setHome_Location_Long__c(contact.getHomeLocAsString());
		if (contact.getSchoolName() != null)
			c.setCurrent_School__c(contact.getSchoolName());
		if (contact.getGenderString() != null)
			c.setGender__c(contact.getGenderString());
		if (contact.getEmergContactName() != null)
			c.setEmergency_Name__c(contact.getEmergContactName());
		if (contact.getEmergContactPhone() != null)
			c.setEmergency_Phone__c(parsePhone(contact.getEmergContactPhone()));
		if (contact.getEmergContactEmail() != null)
			c.setEmergency_Email__c(contact.getEmergContactEmail());
		if (contact.getCurrGrade() != null)
			c.setGrade__c(contact.getCurrGrade());
		if (contact.getGradYearString() != null)
			c.setGrad_Year__c(contact.getGradYearString());
		if (contact.getGithubName() != null)
			c.setGIT_HUB_acct_name__c(contact.getGithubName());
		if (contact.getGrantInfo() != null)
			c.setGrant_Information__c(contact.getGrantInfo());
		c.setLeave_Reason__c(contact.getLeaveReason());
		c.setStop_Email__c(contact.isStopEmail());
		c.setScholarship__c(contact.isFinancialAid());
		if (contact.getFinancialAidPercent() == null || contact.getFinancialAidPercent() == "")
			c.setScholarship_Percentage__c(" ");
		else
			c.setScholarship_Percentage__c(contact.getFinancialAidPercent());
		if (contact.getBirthDate() != null && !contact.getBirthDate().equals(""))
			c.setDate_of_Birth__c(convertDateStringToCalendar(contact.getBirthDate()));

		c.setStudent_Address__c (contact.studAddrIfDiff);
		c.setEthnicity_of_Student__c (contact.studEthnicity);
		c.setPreferred_Language_of_Contact__c (contact.prefContactLang);
		c.setPreferred_Method_of_Contact__c (contact.prefContactMethod);
		c.setPrimary_Language__c (contact.primaryLanguage);
		c.setRace_of_Student__c (contact.studRace);
		c.setRelationship_to_Student__c (contact.relationToStudent);

		return c;
	}

	private Account updateAccountRecord (StudentImportModel contact, Account account) {
		// Create account and add fields
		Account a = new Account();

		a.setId (account.getId());
		a.setName(account.getName());
		a.setAddress_same_as_above__c (contact.studAddrSame);
		a.setAny_Questions_Comments__c (contact.questions);
		a.setAnyone_in_family_work_with_Computers__c (contact.workComputers ? "Yes" : "No");
		a.setEmergency_Contact_Relationship_to_Studen__c (contact.emergRelationToStud);
		a.setHighest_School_completed_Parent_1__c (contact.schoolLevel1);
		a.setHighest_School_Completed_Parent_2__c (contact.schoolLevel2);
		if (contact.prefClassLoc != null && !contact.prefClassLoc.equals("None"))
			a.setPreferred_League_Class_Location__c (contact.prefClassLoc);
		a.setTechnologies_Student_has_access_to__c (contact.techAccess);
		a.setWhere_did_you_hear_about_us__c (contact.getHearAboutUs());
		a.setWho_can_we_thank__c (contact.getWhoToThank());
		
		return a;
	}

	private Contact createStaffRecord(StaffMemberModel staff, String firstName, String clientID, String accountID,
			String contactType) {
		// Create contact and add fields
		Contact c = new Contact();

		c.setAccountId(accountID);
		c.setFront_Desk_Id__c(clientID);
		c.setFirstName(camelCase(firstName.trim()));
		c.setLastName(camelCase(staff.getLastName().trim()));
		c.setContact_Type__c(contactType);
		if (staff.getEmail() != null && contactType.equals("Adult"))
			c.setEmail(staff.getEmail());
		if (staff.getAlternateEmail() != null)
			c.setAlternate_Email__c(staff.getAlternateEmail());
		if (staff.getAddress() != null) {
			c.setFull_Address__c(staff.getAddress());
			Address addr = parseAddress(staff.getAddress());
			if (addr != null) {
				c.setMailingStreet(addr.getStreet());
				c.setMailingCity(addr.getCity());
				c.setMailingState("California");
				c.setMailingCountry("United States");
				c.setMailingPostalCode(addr.getPostalCode());
			}
		}
		if (staff.getPhone() != null)
			c.setMobilePhone(parsePhone(staff.getPhone()));
		if (staff.getHomePhone() != null)
			c.setHomePhone(parsePhone(staff.getHomePhone()));
		if (staff.getCategory() != null)
			c.setStaff_Category__c(staff.getCategory());
		if (staff.getRole() != null)
			c.setRole__c(staff.getRole());
		if (staff.getEmergEmail() != null)
			c.setEmergency_Email__c(staff.getEmergEmail());
		if (staff.getEmergName() != null)
			c.setEmergency_Name__c(staff.getEmergName());
		if (staff.getEmergPhone() != null)
			c.setEmergency_Phone__c(parsePhone(staff.getEmergPhone()));
		if (staff.getEmployer() != null)
			c.setEmployer__c(staff.getEmployer());
		if (staff.getGender() != null)
			c.setGender__c(staff.getGender());
		if (staff.getGithubName() != null)
			c.setGIT_HUB_acct_name__c(staff.getGithubName());
		if (staff.getHomeLocation() != null && contactType.equals("Adult"))
			c.setHome_Location_Long__c(staff.getHomeLocation());
		c.setPast_Staff_Events__c((double) staff.getPastEvents());
		c.setFuture_Staff_Events__c((double) staff.getFutureEvents());
		c.setKey_Holder__c(staff.getKeyHolder());
		if (staff.getLeave() != null)
			c.setStaff_Leave_Reason__c(staff.getLeave());
		if (staff.getOccupation() != null)
			c.setOccupation__c(staff.getOccupation());
		if (staff.getStartInfo() != null)
			c.setAdditional_Details_1__c(staff.getStartInfo());
		if (staff.getWhereDidYouHear() != null)
			c.setHow_you_heard_about_us__c(staff.getWhereDidYouHear());
		if (staff.getTitle() != null && !staff.getTitle().trim().equals(""))
			c.setTItle__c(staff.getTitle());

		// Convert dates
		if (staff.getLiveScan() != null && !staff.getLiveScan().equals(""))
			c.setLiveScan_Date__c(convertDateStringToCalendar(staff.getLiveScan()));
		if (staff.getBirthdate() != null && !staff.getBirthdate().equals(""))
			c.setDate_of_Birth__c(convertDateStringToCalendar(staff.getBirthdate()));

		return c;
	}

	private boolean createAccountRecord(String firstName, String lastName, int clientID, Account account) {
		SaveResult[] saveResults = null;
		Account[] acctList = new Account[] { account };

		try {
			// Update the account records in Salesforce.com
			saveResults = connection.create(acctList);

		} catch (ConnectionException e) {
			if (e.getMessage() == null || e.getMessage().equals("null")) {
				MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_UPSERT_ACCOUNT_ERROR,
						new StudentNameModel(firstName, lastName, false), clientID, " for " + account.getName());
				e.printStackTrace();
			} else
				MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_UPSERT_ACCOUNT_ERROR,
						new StudentNameModel(firstName, lastName, false), clientID,
						" for " + account.getName() + ": " + e.getMessage());
			return false;
		}

		// check the returned results for any errors
		if (saveResults[0].isSuccess()) {
			System.out.println("Created SalesForce account for " + firstName + " " + lastName + ": " + account.getName());
//			MySqlDbLogging.insertLogData(LogDataModel.CREATE_SALES_FORCE_ACCOUNT,
//					new StudentNameModel(firstName, lastName, false), clientID,
//					" for " + firstName + " " + lastName + ": " + account.getName());
			return true;

		} else {
			Error[] errors = saveResults[0].getErrors();
			for (int j = 0; j < errors.length; j++) {
				MySqlDbLogging.insertLogData(LogDataModel.SALES_FORCE_UPSERT_ACCOUNT_ERROR,
						new StudentNameModel(firstName, lastName, false), clientID,
						" for " + account.getName() + ": " + errors[j].getMessage());
			}
			return false;
		}
	}

	private void createWorkshopByTeacherRecord(ArrayList<Workshop_by_Teacher__c> wshopList,
			Student_Attendance__c sfAttend, Contact contact) {
		String uniqueID = ((Integer) sfAttend.getService_Date__c().get(Calendar.YEAR)).toString() 
				+ ((Integer) sfAttend.getService_Date__c().get(Calendar.MONTH)).toString()
				+ ((Integer) sfAttend.getService_Date__c().get(Calendar.DAY_OF_MONTH)).toString() 
				+ contact.getFront_Desk_Id__c();
		
		// Add a record for each teacher of this workshop. Mark a single record for record filtering.
		boolean firstTeach = true;
		firstTeach = addWorkshopRecord(wshopList, firstTeach, sfAttend, contact, uniqueID, sfAttend.getTeacher_1__c().trim());
		firstTeach = addWorkshopRecord(wshopList, firstTeach, sfAttend, contact, uniqueID, sfAttend.getTeacher_2__c().trim());
		firstTeach = addWorkshopRecord(wshopList, firstTeach, sfAttend, contact, uniqueID, sfAttend.getTeacher_3_Vol__c().trim());
		addWorkshopRecord(wshopList, firstTeach, sfAttend, contact, uniqueID, sfAttend.getTeacher_4_Vol__c().trim());
	}
	
	private boolean addWorkshopRecord(ArrayList<Workshop_by_Teacher__c> wshopList, boolean firstFlag,
			Student_Attendance__c sfAttend, Contact contact, String uniqueID, String teacherName) {
		
		if (!teacherName.equals("")) {
			Workshop_by_Teacher__c wshop = new Workshop_by_Teacher__c();
			
			wshop.setVisit_Marker__c(firstFlag);
			wshop.setEvent_Name__c(sfAttend.getEvent_Name__c());
			wshop.setService_Date__c(sfAttend.getService_Date__c());
			wshop.setWShop_Contact__r(contact);
			wshop.setTeacher__c(teacherName);
			wshop.setUnique_Id__c(uniqueID + teacherName.replaceAll("\\s",""));
			
			wshopList.add(wshop);
			return false;
			
		} else
			return firstFlag;
	}

	private String getStaffAccountID(StaffMemberModel staff, ArrayList<Account> sfAccounts) {

		// Check if staff member already has an account ID
		if (staff.getAccountID() != null && !staff.getAccountID().equals(""))
			return staff.getAccountID();

		// Check if student-teacher or parent
		if (staff.getFirstName().startsWith("TA-") || staff.getFirstName().toLowerCase().startsWith("trainee-") 
				|| staff.isAlsoClient()) {
			// All TA's and existing clients must already have an account
			MySqlDbLogging.insertLogData(LogDataModel.MISSING_ACCOUNT_FOR_TA_OR_PARENT,
					new StudentNameModel(staff.getFullName(), "", false), 0, " " + staff.getFullName());
			return null;
		}

		// Create account family name and check whether it already exists
		String acctFamilyName = camelCase(staff.getLastName() + " " + staff.getFirstName() + " Family");
		Account account = ListUtilities.findAccountByName(acctFamilyName, sfAccounts);
		if (account != null) {
			// Account name already exists
			return account.getId();
		}

		// New volunteer/teacher: create an account
		account = new Account();
		account.setName(acctFamilyName);
		account.setType("Family");
		if (!createAccountRecord(staff.getFirstName(), staff.getLastName(), Integer.parseInt(staff.getClientID()),
				account))
			return null;

		// Now that account has been created, need to get account from SF again
		account = getRecords.getSalesForceAccountByName(acctFamilyName);
		if (account == null || account.getName().equals("")) {
			MySqlDbLogging.insertLogData(LogDataModel.UPSERTED_ACCOUNT_RETRIEVAL_ERROR,
					new StudentNameModel(staff.getFullName(), "", false), 0, " for " + acctFamilyName);
			return null;
		}

		// Add to SF account list
		sfAccounts.add(account);
		return account.getId();
	}

	private static String parsePhone(String origPhone) {
		String phone = origPhone.trim();
		if (phone.length() < 10)
			return origPhone;

		if (phone.length() == 10 && phone.indexOf('(') == -1 && phone.indexOf('-') == -1)
			phone = "(" + phone.substring(0, 3) + ")" + " " + phone.substring(3, 6) + "-" + phone.substring(6);
		else if (phone.length() == 12 && (phone.charAt(3) == '-' || phone.charAt(3) == '.' || phone.charAt(3) == ' ')
				&& (phone.charAt(7) == '-' || phone.charAt(7) == '.' || phone.charAt(7) == ' '))
			phone = "(" + phone.substring(0, 3) + ")" + " " + phone.substring(4, 7) + "-" + phone.substring(8);
		else if (phone.length() == 13 && phone.charAt(0) == '(' && phone.charAt(4) == ')' && phone.charAt(8) == '-')
			phone = phone.substring(0, 5) + " " + phone.substring(5);

		return phone;
	}

	private static Address parseAddress(String origAddress) {
		Address mailAddr = new Address();
		String address = origAddress;

		// Replace all CR/LF characters in address
		if (address.contains("\\r") || address.contains("\\n")) {
			address = address.replace(",\\r\\n", ", ");
			address = address.replace("\\r\\n", ", ");
			address = address.replace("\\n", ", ");
		}

		// Find street by looking for next comma
		int idx = address.indexOf(',');
		if (idx <= 0)
			return null;

		mailAddr.setStreet(address.substring(0, idx).trim());
		address = address.substring(idx + 1).trim();

		// Find city by looking for next comma
		idx = address.indexOf(',');
		if (idx <= 0)
			return null;

		mailAddr.setCity(address.substring(0, idx).trim());
		address = address.substring(idx + 1).trim();

		// State may be followed by a comma or a space "CA, 92130" or "CA 92130",
		// or sometimes "CA 92130, United States".
		// So finding the first comma does not always work.
		int idx1 = address.indexOf(',');
		int idx2 = address.indexOf(' ');
		if (idx2 <= 0)
			return null;

		if (idx1 > 0) { // comma and space both exist
			if (idx1 < idx2)
				idx = idx1;
			else
				idx = idx2;
		} else // only a space exists
			idx = idx2;

		// Found state, but only handling California
		String state = address.substring(0, idx).trim();
		if (!state.equalsIgnoreCase("CA") && !state.equalsIgnoreCase("California") && !state.equalsIgnoreCase("CA.")) {
			return null;
		}

		mailAddr.setState("CA");
		address = address.substring(idx + 1).trim();

		// Find zip, make sure not to include country
		if (address.length() < 5)
			return null;

		// Check that zip code is all numeric
		String zip = address.substring(0, 5);
		if (!zip.matches("\\d+"))
			return null;

		mailAddr.setPostalCode(zip);
		return mailAddr;
	}

	private String parseTeacherString(String teachers, Student_Attendance__c attend,
			ArrayList<StaffMemberModel> staffList) {

		// Clear the old values in SalesForce record.
		// Note: Set field won't work if string empty!
		attend.setTeacher_1__c(" ");
		attend.setTeacher_2__c(" ");
		attend.setTeacher_3_Vol__c(" ");
		attend.setTeacher_4_Vol__c(" ");

		// Check for missing teacher record
		if (teachers == null || teachers.equals(""))
			return teachers;

		// Parse teachers into CSV
		String newTeachers = "";
		String[] values = teachers.split("\\s*,\\s*");
		String studentTA = "";

		for (int i = 0; i < values.length; i++) {
			// Ignore unwanted field values (only want 'Sub Teacher')
			String valueLowerCase = values[i].toLowerCase();
			if (valueLowerCase.startsWith("league admin") || valueLowerCase.startsWith("summer prog")
					|| valueLowerCase.startsWith("intro to java")   || valueLowerCase.startsWith("padres") 
					|| valueLowerCase.startsWith("coding camp")     || valueLowerCase.startsWith("open lab") 
					|| valueLowerCase.startsWith("make-up")         || valueLowerCase.startsWith("sub teacher")
					|| valueLowerCase.startsWith("need assist")    	|| valueLowerCase.startsWith("needs assist")   
					|| valueLowerCase.startsWith("needs teacher")   || valueLowerCase.startsWith("league workshop") 
					|| valueLowerCase.startsWith("accepting students"))
				continue;

			// Add teacher to new teachers string
			if (newTeachers.length() > 0)
				newTeachers += ", ";
			newTeachers += values[i];

			// Only add staff members to teacher fields
			StaffMemberModel staff = ListUtilities.findStaffNameInList(values[i], staffList);
			if (staff == null)
				continue;

			if (staff.getCategory().equals("Teaching Staff")) {
				if (attend.getTeacher_1__c().trim().equals(""))
					attend.setTeacher_1__c(values[i]);
				else if (attend.getTeacher_2__c().trim().equals(""))
					attend.setTeacher_2__c(values[i]);

			} else if (staff.getCategory().equals("Vol Teacher")) {
				if (attend.getTeacher_3_Vol__c().trim().equals(""))
					attend.setTeacher_3_Vol__c(values[i]);
				else if (attend.getTeacher_4_Vol__c().trim().equals(""))
					attend.setTeacher_4_Vol__c(values[i]);
				
			} else if (staff.getCategory().equals("Student TA") && studentTA.equals(""))
				studentTA = values[i];
		}
		
		// If no teachers, then add TA
		if (!studentTA.equals("") && attend.getTeacher_1__c().trim().equals("") && attend.getTeacher_2__c().trim().equals("")
				&& attend.getTeacher_3_Vol__c().trim().equals("") && attend.getTeacher_4_Vol__c().trim().equals("")) {
			attend.setTeacher_4_Vol__c(studentTA);
		}

		return newTeachers;
	}

	private static Calendar convertDateStringToCalendar(String dateString) {
		DateTime date = new DateTime(dateString);
		Calendar cal = Calendar.getInstance();
		cal.setTimeInMillis(date.getMillis());
		return cal;
	}

	private static String getDateInPastByWeeks(int minusNumWeeks) {
		DateTime date = new DateTime().withZone(DateTimeZone.forID("America/Los_Angeles")).minusWeeks(minusNumWeeks);
		return date.toString("yyyy-MM-dd");
	}

	private static void updateFamilyEmail(StudentImportModel model, Contact c) {
		String emails = "";

		// Concatenate student email, account manager emails & emergency email
		if (model.getEmail() != null && !model.getEmail().equals(""))
			emails += model.getEmail().toLowerCase().trim();
		if (model.getAccountMgrEmails() != null && !model.getAccountMgrEmails().equals("")
				&& !emails.contains(model.getAccountMgrEmails())) {
			if (!emails.equals(""))
				emails += ", ";
			emails += model.getAccountMgrEmails().toLowerCase().trim();
		}
		if (model.getEmergContactEmail() != null && !model.getEmergContactEmail().equals("")
				&& !emails.contains(model.getEmergContactEmail())) {
			if (!emails.equals(""))
				emails += ", ";
			emails += model.getEmergContactEmail().toLowerCase().trim();
		}

		// Now add email string to SalesForce contact
		if (!emails.equals(""))
			c.setFamily_Email__c(emails);
	}

	private int getNumClassesByLevel(int clientID, String level) {
		try {
			// Get all SalesForce diary entries with non-null 'clientID + level' key
			QueryResult queryResults = connection
					.query("SELECT Front_Desk_Id__c, Internal_Level__c FROM Student_Attendance__c "
							+ "WHERE Front_Desk_Id__c = '" + clientID + "' AND Internal_Level__c = '" + level + "'");
			return queryResults.getSize();

		} catch (Exception e) {
			if (e.getMessage() == null) {
				e.printStackTrace();
				MySqlDbLogging.insertLogData(LogDataModel.SF_DIARY_IMPORT_ERROR, new StudentNameModel("", "", false),
						clientID, " for Level " + level);

			} else
				MySqlDbLogging.insertLogData(LogDataModel.SF_DIARY_IMPORT_ERROR,
						new StudentNameModel("", "", false), clientID, " for Level " + level + ": " + e.getMessage());
		}
		return 0;
	}

	private String camelCase(String input) {
		// Capitalize first letter
		int currIdx = 1, spaceIdx;
		String output = input.substring(0, 1).toUpperCase() + input.substring(1);

		while (true) {
			// Find next space
			spaceIdx = output.substring(currIdx).indexOf(' ');
			if (spaceIdx < 0) {
				break;
			}

			// Capitalize character after the space
			currIdx += spaceIdx + 1;
			output = output.substring(0, currIdx) + output.substring(currIdx, currIdx + 1).toUpperCase()
					+ output.substring(currIdx + 1);
			currIdx++;
		}
		return output;
	}
}
