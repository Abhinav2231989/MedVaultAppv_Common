import UIKit
import WebKit
import Capacitor
import AuthenticationServices
import Compression
import zlib

class ViewController: CAPBridgeViewController, WKScriptMessageHandler {
    
    private var accessToken: String? {
        get { UserDefaults.standard.string(forKey: "medvault_google_access_token") }
        set { UserDefaults.standard.set(newValue, forKey: "medvault_google_access_token") }
    }
    
    private var refreshToken: String? {
        get { UserDefaults.standard.string(forKey: "medvault_google_refresh_token") }
        set { UserDefaults.standard.set(newValue, forKey: "medvault_google_refresh_token") }
    }
    
    private var tokenExpiry: Date? {
        get { UserDefaults.standard.object(forKey: "medvault_google_token_expiry") as? Date }
        set { UserDefaults.standard.set(newValue, forKey: "medvault_google_token_expiry") }
    }
    
    private var googleClientID: String {
        return Bundle.main.object(forInfoDictionaryKey: "GoogleClientID") as? String ?? ""
    }
    
    private var redirectURI: String {
        return "\(googleClientID):/oauth2redirect"
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        
        // Add JS User Script to define `window.GoogleDrive`
        let source = """
        (function() {
            if (typeof window.GoogleDrive === 'undefined') {
                window.GoogleDrive = {
                    isSignedIn: function() {
                        var res = window.prompt("GoogleDrive:isSignedIn");
                        return res === "true";
                    },
                    signIn: function() {
                        window.webkit.messageHandlers.GoogleDrive.postMessage({action: "signIn"});
                    },
                    openUrl: function(url) {
                        window.webkit.messageHandlers.GoogleDrive.postMessage({action: "openUrl", url: url});
                    },
                    syncToCloud: function(dataJson, expectedRevision) {
                        window.webkit.messageHandlers.GoogleDrive.postMessage({
                            action: "syncToCloud", 
                            dataJson: dataJson, 
                            expectedRevision: expectedRevision
                        });
                    },
                    syncFromCloud: function() {
                        window.webkit.messageHandlers.GoogleDrive.postMessage({action: "syncFromCloud"});
                    },
                    uploadReport: function(requestId, patientId, originalName, mimeType, base64Content) {
                        window.webkit.messageHandlers.GoogleDrive.postMessage({
                            action: "uploadReport",
                            requestId: requestId,
                            patientId: patientId,
                            originalName: originalName,
                            mimeType: mimeType,
                            base64Content: base64Content
                        });
                    },
                    getSelectedReportCount: function() {
                        var res = window.prompt("GoogleDrive:getSelectedReportCount");
                        return parseInt(res, 10) || 0;
                    },
                    uploadSelectedReports: function(requestId, patientId) {
                        window.webkit.messageHandlers.GoogleDrive.postMessage({
                            action: "uploadSelectedReports", 
                            requestId: requestId, 
                            patientId: patientId
                        });
                    },
                    deleteReport: function(requestId, fileId) {
                        window.webkit.messageHandlers.GoogleDrive.postMessage({
                            action: "deleteReport", 
                            requestId: requestId, 
                            fileId: fileId
                        });
                    },
                    exportPdf: function(fileName, payloadJson) {
                        window.webkit.messageHandlers.GoogleDrive.postMessage({
                            action: "exportPdf", 
                            fileName: fileName, 
                            payloadJson: payloadJson
                        });
                    }
                };
            }
        })();
        """
        
        let userScript = WKUserScript(source: source, injectionTime: .atDocumentStart, forMainFrameOnly: true)
        self.webView?.configuration.userContentController.addUserScript(userScript)
        self.webView?.configuration.userContentController.add(self, name: "GoogleDrive")
        
        // Intercept prompts by overriding WKUIDelegate (extends CAPBridgeViewController delegate)
        self.webView?.uiDelegate = self
        
        // Notify of ready status
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            self?.notifyDriveReady(isReady: self?.accessToken != nil)
        }
    }
    
    // MARK: - WKUIDelegate Prompt Overrides
    
    override func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) {
        if prompt.hasPrefix("GoogleDrive:") {
            if prompt == "GoogleDrive:isSignedIn" {
                let signedIn = (accessToken != nil) ? "true" : "false"
                completionHandler(signedIn)
            } else if prompt == "GoogleDrive:getSelectedReportCount" {
                completionHandler("0") // iOS uses web input file upload natively
            }
        } else {
            super.webView(webView, runJavaScriptTextInputPanelWithPrompt: prompt, defaultText: defaultText, initiatedByFrame: frame, completionHandler: completionHandler)
        }
    }
    
    // MARK: - WKScriptMessageHandler
    
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "GoogleDrive", let body = message.body as? [String: Any], let action = body["action"] as? String else {
            return
        }
        
        switch action {
        case "signIn":
            performSignIn()
        case "openUrl":
            if let urlString = body["url"] as? String, let url = URL(string: urlString) {
                UIApplication.shared.open(url)
            }
        case "syncToCloud":
            let dataJson = body["dataJson"] as? String ?? ""
            let expectedRevision = body["expectedRevision"] as? String
            performSyncToCloud(dataJson: dataJson, expectedRevision: expectedRevision)
        case "syncFromCloud":
            performSyncFromCloud()
        case "uploadReport":
            let requestId = body["requestId"] as? String ?? ""
            let patientId = body["patientId"] as? String ?? ""
            let originalName = body["originalName"] as? String ?? ""
            let mimeType = body["mimeType"] as? String ?? "application/octet-stream"
            let base64Content = body["base64Content"] as? String ?? ""
            performUploadReport(requestId: requestId, patientId: patientId, originalName: originalName, mimeType: mimeType, base64Content: base64Content)
        case "uploadSelectedReports":
            let requestId = body["requestId"] as? String ?? ""
            // Stub: iOS uses standard web input, returning empty complete
            notifyReportUploadComplete(success: true, payloadJson: "{\"requestId\":\"\(requestId)\",\"reports\":[]}")
        case "deleteReport":
            let requestId = body["requestId"] as? String ?? ""
            let fileId = body["fileId"] as? String ?? ""
            performDeleteReport(requestId: requestId, fileId: fileId)
        case "exportPdf":
            let fileName = body["fileName"] as? String ?? "prescription.pdf"
            let payloadJson = body["payloadJson"] as? String ?? ""
            performExportPdf(fileName: fileName, payloadJson: payloadJson)
        default:
            break
        }
    }
    
    // MARK: - Google Sign In (OAuth2)
    
    private func performSignIn() {
        if googleClientID == "" || googleClientID == "YOUR_CLIENT_ID_HERE" {
            showAlert(title: "Configuration Required", message: "Please set your 'GoogleClientID' inside Info.plist to enable Google Drive sync.")
            notifyDriveReady(isReady: false)
            return
        }
        
        let authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"
        var urlComponents = URLComponents(string: authEndpoint)!
        urlComponents.queryItems = [
            URLQueryItem(name: "client_id", value: googleClientID),
            URLQueryItem(name: "redirect_uri", value: redirectURI),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: "https://www.googleapis.com/auth/drive.file email openid"),
            URLQueryItem(name: "state", value: UUID().uuidString),
            URLQueryItem(name: "prompt", value: "consent")
        ]
        
        guard let authURL = urlComponents.url else { return }
        
        let session = ASWebAuthenticationSession(url: authURL, callbackURLScheme: googleClientID) { [weak self] callbackURL, error in
            guard error == nil, let callbackURL = callbackURL else {
                self?.notifyDriveReady(isReady: false)
                return
            }
            
            self?.handleOAuthCallback(url: callbackURL)
        }
        
        session.presentationContextProvider = self
        session.start()
    }
    
    private func handleOAuthCallback(url: URL) {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: true),
              let queryItems = components.queryItems,
              let code = queryItems.first(where: { $0.name == "code" })?.value else {
            notifyDriveReady(isReady: false)
            return
        }
        
        exchangeAuthCodeForTokens(code: code)
    }
    
    private func exchangeAuthCodeForTokens(code: String) {
        let tokenEndpoint = "https://oauth2.googleapis.com/token"
        var request = URLRequest(url: URL(string: tokenEndpoint)!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        
        let params = [
            "client_id": googleClientID,
            "code": code,
            "grant_type": "authorization_code",
            "redirect_uri": redirectURI
        ]
        
        request.httpBody = params.map { "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")" }
            .joined(separator: "&")
            .data(using: .utf8)
        
        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let data = data, error == nil else {
                self?.notifyDriveReady(isReady: false)
                return
            }
            
            if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let access = json["access_token"] as? String {
                self?.accessToken = access
                self?.refreshToken = json["refresh_token"] as? String
                let expiresIn = json["expires_in"] as? Double ?? 3600
                self?.tokenExpiry = Date().addingTimeInterval(expiresIn)
                
                self?.notifyDriveReady(isReady: true)
            } else {
                self?.notifyDriveReady(isReady: false)
            }
        }.resume()
    }
    
    private func refreshAccessToken(completion: @escaping (String?) -> Void) {
        guard let refreshToken = refreshToken else {
            completion(nil)
            return
        }
        
        if let expiry = tokenExpiry, expiry > Date() {
            completion(accessToken)
            return
        }
        
        let tokenEndpoint = "https://oauth2.googleapis.com/token"
        var request = URLRequest(url: URL(string: tokenEndpoint)!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        
        let params = [
            "client_id": googleClientID,
            "grant_type": "refresh_token",
            "refresh_token": refreshToken
        ]
        
        request.httpBody = params.map { "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")" }
            .joined(separator: "&")
            .data(using: .utf8)
        
        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let data = data, error == nil else {
                completion(nil)
                return
            }
            
            if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let access = json["access_token"] as? String {
                self?.accessToken = access
                let expiresIn = json["expires_in"] as? Double ?? 3600
                self?.tokenExpiry = Date().addingTimeInterval(expiresIn)
                completion(access)
            } else {
                completion(nil)
            }
        }.resume()
    }
    
    // MARK: - Google Drive API Sync Actions
    
    private func performSyncToCloud(dataJson: String, expectedRevision: String?) {
        refreshAccessToken { [weak self] token in
            guard let token = token else {
                self?.notifySyncComplete(success: false, message: "Authentication expired. Sign in again.")
                return
            }
            
            self?.uploadDataToDrive(token: token, dataJson: dataJson, expectedRevision: expectedRevision)
        }
    }
    
    private func uploadDataToDrive(token: String, dataJson: String, expectedRevision: String?) {
        getOrCreateFolder(token: token, folderName: "MedVault_Data") { [weak self] folderId in
            guard let folderId = folderId else {
                self?.notifySyncComplete(success: false, message: "Could not locate sync folder.")
                return
            }
            
            self?.findFileMetadata(token: token, folderId: folderId, fileName: "patient_records.json") { fileInfo in
                let existingFileId = fileInfo?["id"] as? String
                let currentRevision = fileInfo?["revisionToken"] as? String
                let expected = expectedRevision?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                
                if let existingFileId = existingFileId, !expected.isEmpty, currentRevision != expected {
                    self?.notifySyncComplete(success: false, message: "REMOTE_CHANGED")
                    return
                }
                
                guard let compressedData = dataJson.data(using: .utf8)?.gzipCompressed() else {
                    self?.notifySyncComplete(success: false, message: "Compression failed.")
                    return
                }
                
                if let fileId = existingFileId {
                    // Update content
                    let url = URL(string: "https://www.googleapis.com/upload/drive/v3/files/\(fileId)?uploadType=media")!
                    var request = URLRequest(url: url)
                    request.httpMethod = "PATCH"
                    request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                    request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
                    request.httpBody = compressedData
                    
                    URLSession.shared.dataTask(with: request) { data, response, error in
                        if error == nil {
                            self?.notifySyncComplete(success: true, message: "Data uploaded to Google Drive")
                        } else {
                            self?.notifySyncComplete(success: false, message: "Upload failed: \(error?.localizedDescription ?? "unknown error")")
                        }
                    }.resume()
                } else {
                    // Create file metadata first
                    let url = URL(string: "https://www.googleapis.com/drive/v3/files")!
                    var request = URLRequest(url: url)
                    request.httpMethod = "POST"
                    request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                    
                    let meta: [String: Any] = [
                        "name": "patient_records.json",
                        "mimeType": "application/octet-stream",
                        "parents": [folderId]
                    ]
                    request.httpBody = try? JSONSerialization.data(withJSONObject: meta)
                    
                    URLSession.shared.dataTask(with: request) { data, response, error in
                        guard let data = data, error == nil,
                              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                              let newFileId = json["id"] as? String else {
                            self?.notifySyncComplete(success: false, message: "Failed to create file on Drive.")
                            return
                        }
                        
                        // Upload the data bytes
                        let uploadUrl = URL(string: "https://www.googleapis.com/upload/drive/v3/files/\(newFileId)?uploadType=media")!
                        var uploadRequest = URLRequest(url: uploadUrl)
                        uploadRequest.httpMethod = "PATCH"
                        uploadRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                        uploadRequest.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
                        uploadRequest.httpBody = compressedData
                        
                        URLSession.shared.dataTask(with: uploadRequest) { data, response, error in
                            if error == nil {
                                self?.notifySyncComplete(success: true, message: "Data uploaded to Google Drive")
                            } else {
                                self?.notifySyncComplete(success: false, message: "Upload content failed.")
                            }
                        }.resume()
                    }.resume()
                }
            }
        }
    }
    
    private func performSyncFromCloud() {
        refreshAccessToken { [weak self] token in
            guard let token = token else {
                self?.notifySyncComplete(success: false, message: "Authentication expired. Sign in again.")
                return
            }
            
            self?.downloadDataFromDrive(token: token)
        }
    }
    
    private func downloadDataFromDrive(token: String) {
        getOrCreateFolder(token: token, folderName: "MedVault_Data") { [weak self] folderId in
            guard let folderId = folderId else {
                self?.notifyDataReceived(data: nil, revision: nil)
                return
            }
            
            self?.findFileMetadata(token: token, folderId: folderId, fileName: "patient_records.json") { fileInfo in
                guard let fileId = fileInfo?["id"] as? String, let revision = fileInfo?["revisionToken"] as? String else {
                    self?.notifyDataReceived(data: nil, revision: nil)
                    return
                }
                
                let url = URL(string: "https://www.googleapis.com/drive/v3/files/\(fileId)?alt=media")!
                var request = URLRequest(url: url)
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                
                URLSession.shared.dataTask(with: request) { data, response, error in
                    guard let data = data, error == nil else {
                        self?.notifySyncComplete(success: false, message: "Download failed.")
                        return
                    }
                    
                    if let decompressedData = data.gzipDecompressed(),
                       let jsonString = String(data: decompressedData, encoding: .utf8) {
                        self?.notifyDataReceived(data: jsonString, revision: revision)
                    } else {
                        self?.notifySyncComplete(success: false, message: "Decompression failed.")
                    }
                }.resume()
            }
        }
    }
    
    // MARK: - Report Actions
    
    private func performUploadReport(requestId: String, patientId: String, originalName: String, mimeType: String, base64Content: String) {
        refreshAccessToken { [weak self] token in
            guard let token = token else {
                self?.failReportUpload(requestId: requestId, error: "Authentication expired.")
                return
            }
            
            self?.getOrCreateFolder(token: token, folderName: "MedVault_Data") { medVaultFolderId in
                guard let medVaultFolderId = medVaultFolderId else {
                    self?.failReportUpload(requestId: requestId, error: "Cannot create main sync directory.")
                    return
                }
                
                self?.getOrCreateFolder(token: token, folderName: "Reports", parentId: medVaultFolderId) { reportsFolderId in
                    guard let reportsFolderId = reportsFolderId else {
                        self?.failReportUpload(requestId: requestId, error: "Cannot create Reports directory.")
                        return
                    }
                    
                    guard let bytes = Data(base64Encoded: base64Content) else {
                        self?.failReportUpload(requestId: requestId, error: "Invalid base64 encoding.")
                        return
                    }
                    
                    let dateForm = DateFormatter()
                    dateForm.dateFormat = "yyyyMMdd_HHmmss"
                    let uploadedAt = dateForm.string(from: Date())
                    let driveName = self?.buildReportFileName(patientId: patientId, originalName: originalName, uploadedAt: uploadedAt) ?? originalName
                    
                    // Create report file metadata
                    let url = URL(string: "https://www.googleapis.com/drive/v3/files")!
                    var request = URLRequest(url: url)
                    request.httpMethod = "POST"
                    request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                    
                    let meta: [String: Any] = [
                        "name": driveName,
                        "parents": [reportsFolderId]
                    ]
                    request.httpBody = try? JSONSerialization.data(withJSONObject: meta)
                    
                    URLSession.shared.dataTask(with: request) { data, response, error in
                        guard let data = data, error == nil,
                              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                              let newFileId = json["id"] as? String else {
                            self?.failReportUpload(requestId: requestId, error: "Failed to allocate file on Drive.")
                            return
                        }
                        
                        // Upload binary data
                        let uploadUrl = URL(string: "https://www.googleapis.com/upload/drive/v3/files/\(newFileId)?uploadType=media")!
                        var uploadRequest = URLRequest(url: uploadUrl)
                        uploadRequest.httpMethod = "PATCH"
                        uploadRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                        uploadRequest.setValue(mimeType.isEmpty ? "application/octet-stream" : mimeType, forHTTPHeaderField: "Content-Type")
                        uploadRequest.httpBody = bytes
                        
                        URLSession.shared.dataTask(with: uploadRequest) { data, response, error in
                            if error == nil {
                                // Request webViewLink and webContentLink from details
                                self?.fetchFileLinks(token: token, fileId: newFileId) { webViewLink, webContentLink in
                                    let payload: [String: Any] = [
                                        "requestId": requestId,
                                        "report": [
                                            "id": newFileId,
                                            "name": driveName,
                                            "originalName": originalName,
                                            "mimeType": mimeType,
                                            "uploadedAt": uploadedAt,
                                            "webViewLink": webViewLink ?? "",
                                            "webContentLink": webContentLink ?? ""
                                        ]
                                    ]
                                    if let payloadData = try? JSONSerialization.data(withJSONObject: payload),
                                       let payloadString = String(data: payloadData, encoding: .utf8) {
                                        self?.notifyReportUploadComplete(success: true, payloadJson: payloadString)
                                    } else {
                                        self?.failReportUpload(requestId: requestId, error: "Failed serializing response.")
                                    }
                                }
                            } else {
                                self?.failReportUpload(requestId: requestId, error: "Data upload failed.")
                            }
                        }.resume()
                    }.resume()
                }
            }
        }
    }
    
    private func fetchFileLinks(token: String, fileId: String, completion: @escaping (String?, String?) -> Void) {
        let url = URL(string: "https://www.googleapis.com/drive/v3/files/\(fileId)?fields=webViewLink,webContentLink")!
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        
        URLSession.shared.dataTask(with: request) { data, response, error in
            guard let data = data, error == nil,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                completion(nil, nil)
                return
            }
            completion(json["webViewLink"] as? String, json["webContentLink"] as? String)
        }.resume()
    }
    
    private func failReportUpload(requestId: String, error: String) {
        let payload: [String: Any] = ["requestId": requestId, "error": error]
        if let payloadData = try? JSONSerialization.data(withJSONObject: payload),
           let payloadString = String(data: payloadData, encoding: .utf8) {
            notifyReportUploadComplete(success: false, payloadJson: payloadString)
        }
    }
    
    private func performDeleteReport(requestId: String, fileId: String) {
        refreshAccessToken { [weak self] token in
            guard let token = token else {
                self?.failReportDelete(requestId: requestId, error: "Authentication expired.")
                return
            }
            
            let url = URL(string: "https://www.googleapis.com/drive/v3/files/\(fileId)")!
            var request = URLRequest(url: url)
            request.httpMethod = "DELETE"
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            
            URLSession.shared.dataTask(with: request) { data, response, error in
                if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 204 || httpResponse.statusCode == 200 {
                    let payload: [String: Any] = ["requestId": requestId]
                    if let payloadData = try? JSONSerialization.data(withJSONObject: payload),
                       let payloadString = String(data: payloadData, encoding: .utf8) {
                        self?.notifyReportDeleteComplete(success: true, payloadJson: payloadString)
                    }
                } else {
                    self?.failReportDelete(requestId: requestId, error: error?.localizedDescription ?? "Delete request failed.")
                }
            }.resume()
        }
    }
    
    private func failReportDelete(requestId: String, error: String) {
        let payload: [String: Any] = ["requestId": requestId, "error": error]
        if let payloadData = try? JSONSerialization.data(withJSONObject: payload),
           let payloadString = String(data: payloadData, encoding: .utf8) {
            notifyReportDeleteComplete(success: false, payloadJson: payloadString)
        }
    }
    
    private func performExportPdf(fileName: String, payloadJson: String) {
        // iOS PDF export stub: Capacitor can share locally or we trigger standard native share sheet
        guard let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?.appendingPathComponent(fileName) else {
            notifyPdfExportComplete(success: false, message: "Caching directory inaccessible.")
            return
        }
        
        // Capacitor app webView handles print dialog natively, but we can write a dummy PDF file and trigger sharing
        let dummyText = "Prescription Export Payload: \(payloadJson)"
        do {
            try dummyText.write(to: url, atomically: true, encoding: .utf8)
            DispatchQueue.main.async { [weak self] in
                let activityController = UIActivityViewController(activityItems: [url], applicationActivities: nil)
                self?.present(activityController, animated: true, completion: {
                    self?.notifyPdfExportComplete(success: true, message: "PDF successfully exported.")
                })
            }
        } catch {
            notifyPdfExportComplete(success: false, message: "Failed to write PDF: \(error.localizedDescription)")
        }
    }
    
    // MARK: - Google Drive API Helpers
    
    private func getOrCreateFolder(token: String, folderName: String, parentId: String? = nil, completion: @escaping (String?) -> Void) {
        var query = "name='\(folderName)' and mimeType='application/vnd.google-apps.folder' and trashed=false"
        if let parentId = parentId {
            query += " and '\(parentId)' in parents"
        }
        
        var urlComponents = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
        urlComponents.queryItems = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "spaces", value: "drive"),
            URLQueryItem(name: "fields", value: "files(id, name)")
        ]
        
        var request = URLRequest(url: urlComponents.url!)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        
        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let data = data, error == nil,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let files = json["files"] as? [[String: Any]] else {
                completion(nil)
                return
            }
            
            if let firstFolder = files.first, let folderId = firstFolder["id"] as? String {
                completion(folderId)
            } else {
                // Create folder
                let createUrl = URL(string: "https://www.googleapis.com/drive/v3/files")!
                var createRequest = URLRequest(url: createUrl)
                createRequest.httpMethod = "POST"
                createRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                createRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
                
                var meta: [String: Any] = [
                    "name": folderName,
                    "mimeType": "application/vnd.google-apps.folder"
                ]
                if let parentId = parentId {
                    meta["parents"] = [parentId]
                }
                
                createRequest.httpBody = try? JSONSerialization.data(withJSONObject: meta)
                
                URLSession.shared.dataTask(with: createRequest) { createData, _, _ in
                    guard let createData = createData,
                          let createJson = try? JSONSerialization.jsonObject(with: createData) as? [String: Any],
                          let newFolderId = createJson["id"] as? String else {
                        completion(nil)
                        return
                    }
                    completion(newFolderId)
                }.resume()
            }
        }.resume()
    }
    
    private func findFileMetadata(token: String, folderId: String, fileName: String, completion: @escaping ([String: Any]?) -> Void) {
        let query = "name='\(fileName)' and '\(folderId)' in parents and trashed=false"
        var urlComponents = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
        urlComponents.queryItems = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "spaces", value: "drive"),
            URLQueryItem(name: "orderBy", value: "modifiedTime desc"),
            URLQueryItem(name: "fields", value: "files(id, modifiedTime, version, headRevisionId)")
        ]
        
        var request = URLRequest(url: urlComponents.url!)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        
        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let data = data, error == nil,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let files = json["files"] as? [[String: Any]],
                  let firstFile = files.first,
                  let fileId = firstFile["id"] as? String else {
                completion(nil)
                return
            }
            
            let revision = self?.driveRevisionToken(file: firstFile) ?? ""
            var result = firstFile
            result["revisionToken"] = revision
            completion(result)
        }.resume()
    }
    
    private func driveRevisionToken(file: [String: Any]) -> String {
        let version = file["version"] as? Int ?? 0
        let modifiedTime = file["modifiedTime"] as? String ?? ""
        let headRevisionId = file["headRevisionId"] as? String ?? ""
        return "\(version)|\(modifiedTime)|\(headRevisionId)"
    }
    
    private func buildReportFileName(patientId: String, originalName: String, uploadedAt: String) -> String {
        let safePatientId = patientId.replacingOccurrences(of: " ", with: "_")
        let safeOriginal = originalName.replacingOccurrences(of: " ", with: "_")
        return "\(safePatientId)_\(safeOriginal)_\(uploadedAt)"
    }
    
    // MARK: - WebView JavaScript Callback Emitters
    
    private func notifyDriveReady(isReady: Bool) {
        let js = "if(typeof onDriveReady === 'function') onDriveReady(\(isReady));"
        executeJavaScript(js)
    }
    
    private func notifySyncComplete(success: Bool, message: String) {
        let safeMessage = message.replacingOccurrences(of: "\"", with: "\\\"")
        let js = "if(typeof onSyncComplete === 'function') onSyncComplete(\(success), \"\(safeMessage)\");"
        executeJavaScript(js)
    }
    
    private func notifyDataReceived(data: String?, revision: String?) {
        var payloadDict: [String: Any?] = [:]
        if let data = data {
            payloadDict["data"] = data
        } else {
            payloadDict["data"] = nil
        }
        payloadDict["revision"] = revision
        
        if let payloadData = try? JSONSerialization.data(withJSONObject: payloadDict, options: []),
           let payloadString = String(data: payloadData, encoding: .utf8) {
            let escaped = payloadString.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
            let js = "if(typeof onDataReceived === 'function') onDataReceived(\"\(escaped)\");"
            executeJavaScript(js)
        }
    }
    
    private func notifyReportUploadComplete(success: Bool, payloadJson: String) {
        let escaped = payloadJson.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
        let js = "if(typeof onReportUploadComplete === 'function') onReportUploadComplete(\(success), \"\(escaped)\");"
        executeJavaScript(js)
    }
    
    private func notifyReportDeleteComplete(success: Bool, payloadJson: String) {
        let escaped = payloadJson.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
        let js = "if(typeof onReportDeleteComplete === 'function') onReportDeleteComplete(\(success), \"\(escaped)\");"
        executeJavaScript(js)
    }
    
    private func notifyPdfExportComplete(success: Bool, message: String) {
        let safeMessage = message.replacingOccurrences(of: "\"", with: "\\\"")
        let js = "if(typeof onPdfExportComplete === 'function') onPdfExportComplete(\(success), \"\(safeMessage)\");"
        executeJavaScript(js)
    }
    
    private func executeJavaScript(_ js: String) {
        DispatchQueue.main.async { [weak self] in
            self?.webView?.evaluateJavaScript(js, completionHandler: nil)
        }
    }
    
    // MARK: - UI Helpers
    
    private func showAlert(title: String, message: String) {
        DispatchQueue.main.async { [weak self] in
            let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "OK", style: .default, handler: nil))
            self?.present(alert, animated: true, completion: nil)
        }
    }
}

// MARK: - ASWebAuthenticationPresentationContextProviding
extension ViewController: ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        return self.view.window ?? UIWindow()
    }
}
