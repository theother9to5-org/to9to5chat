(ns theother9to5.functions.chat
  (:require [cljs.nodejs :as node]
            [cljs.core.async :refer [go timeout]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [clojure.string :as string]
            [clojure.set :as sets]
            [cognitect.transit :as t]
            [promesa.core :as p]
            [theother9to5.utils :as utils]
            [theother9to5.cloud-tasks :as cloud-tasks]
            [theother9to5.firestore :as firestore]))

(goog-define GEMINI_KEYS_PATH "./gemini-ai.json")
(goog-define BOT_NAME "theother9to5-bot")
(goog-define AGENTS_MD_PATH "./AGENTS.md")

(defonce ^CloudFunctionsFramework cloud-functions-framework
  (node/require "@google-cloud/functions-framework"))
(defonce ^GoogleGenAI google-gen-ai
  (node/require "@google/genai"))
(defonce ^FsPromises fs (node/require "fs"))

(defonce t-reader (t/reader :json))
(defonce t-writer (t/writer :json))

(defonce ai-client (atom nil))
(defonce pattern #"(.*)\[([^\]]*)\]$")
(defonce system-prompt-cache (atom nil))
(defonce prompt-cache (atom {}))

(defonce wait-to-email-time (* 7 60)) ; minutes
(defonce queue-name "email")

(defn handle-name-code
  "Updates the user's name in the cache and Firestore."
  [session-id data]
  (p/create
   (fn [resolve reject]
     (go
       (try
         (swap! prompt-cache assoc-in [session-id :user :name] data)
         (<p! (firestore/upsert-doc "chats" session-id {:name data}))
         (resolve nil)
         (catch :default e (reject e)))))))

(defn handle-email-code
  "Updates the user's email in the cache and Firestore, and schedules an email task if one wasn't already set."
  [session-id data]
  (p/create
   (fn [resolve reject]
     (go
       (try
         (if-not (get-in @prompt-cache [session-id :user :email])
           (<p! (cloud-tasks/create-task queue-name "send-email" {:template "contact-info-shared" :sid session-id} wait-to-email-time)))
         (swap! prompt-cache assoc-in [session-id :user :email] data)
         (<p! (firestore/upsert-doc "chats" session-id {:email data}))
         (resolve nil)
         (catch :default e (reject e)))))))

(defn handle-name-email-code
  "Updates both name and email in the cache and Firestore, scheduling an admin ping email if no email was previously set."
  [session-id data]
  (p/create
   (fn [resolve reject]
     (go
       (try
         (let [[name email] (string/split data ";")]
           (if-not (get-in @prompt-cache [session-id :user :email])
             (<p! (cloud-tasks/create-task queue-name "send-email" {:template "contact-info-shared" :sid session-id} wait-to-email-time)))
           (swap! prompt-cache assoc-in [session-id :user :name] name)
           (swap! prompt-cache assoc-in [session-id :user :email] email)
           (<p! (firestore/upsert-doc "chats" session-id {:name name :email email})))
         (resolve nil)
         (catch :default e (reject e)))))))

(defn handle-concept-code
  "Appends a new concept to the user's list of concepts, keeping the last 5, and updates cache and Firestore."
  [session-id data]
  (p/create
   (fn [resolve reject]
     (go
       (try
         (let [chat-doc (<p! (firestore/get-doc "chats" session-id))
               updated-concepts (take-last 5 (conj (or (:concepts chat-doc) []) data))]
           (swap! prompt-cache assoc-in [session-id :user :concepts] updated-concepts)
           (<p! (firestore/upsert-doc "chats" session-id {:concepts updated-concepts})))
         (resolve nil)
         (catch :default e (reject e)))))))

(defn handle-name-opt-out-code
  "Marks the user as having opted out of providing a name in the cache and Firestore."
  [session-id]
  (p/create
   (fn [resolve reject]
     (go
       (try
         (swap! prompt-cache assoc-in [session-id :user :name-opt-out] true)
         (swap! prompt-cache utils/dissoc-in [session-id :user :name])
         (<p! (firestore/upsert-doc "chats" session-id {:name nil :name-opt-out true}))
         (resolve nil)
         (catch :default e (reject e)))))))

(defn handle-email-opt-out-code
  "Marks the user as having opted out of providing an email in the cache and Firestore."
  [session-id]
  (p/create
   (fn [resolve reject]
     (go
       (try
         (swap! prompt-cache assoc-in [session-id :user :email-opt-out] true)
         (swap! prompt-cache utils/dissoc-in [session-id :user :email])
         (<p! (firestore/upsert-doc "chats" session-id {:email nil :email-opt-out true}))
         (resolve nil)
         (catch :default e (reject e)))))))

(defn handle-opt-out-code
  "Deletes the user's chat data and marks them as opted out."
  [session-id]
  (p/create
   (fn [resolve reject]
     (go
       (try
         (swap! prompt-cache dissoc session-id)
         (swap! prompt-cache assoc-in [session-id :user :opt-out] true)
         (<p! (firestore/delete-doc "chats" session-id))
         (resolve nil)
         (catch :default e (reject e)))))))

(defn handle-code
  "Dispatches to the appropriate code handler based on the command extracted from the code string."
  [session-id code]
  (p/create
   (fn [resolve reject]
     (go
       (try
         (let [[command data] (string/split code ":")]
           (condp = command
             "name"          (<p! (handle-name-code session-id data))
             "email"         (<p! (handle-email-code session-id data))
             "name-email"    (<p! (handle-name-email-code session-id data))
             "concept"       (<p! (handle-concept-code session-id data))
             "name-opt-out"  (<p! (handle-name-opt-out-code session-id))
             "email-opt-out" (<p! (handle-email-opt-out-code session-id))
             "opt-out"       (<p! (handle-opt-out-code session-id))
             nil)
           (resolve nil))
         (catch :default error
           (js/console.error "Error handling chatbot response code:" error)
           (reject error)))))))

(defn get-system-prompt
  "Retrieves the system prompt from the cache or reads it from the file system, enriching it with user info from Firestore."
  [session-id file-path]
  (p/create
   (fn [resolve reject]
     (go
       (try
         (if-let [cached-prompt (get @prompt-cache session-id)]
           (resolve cached-prompt)
           (let [system-prompt
                 (or @system-prompt-cache
                     (.readFileSync fs file-path "utf-8"))
                 user-info (<p! (firestore/get-doc "chats" session-id))
                 prompt {:system-prompt system-prompt :user user-info}]
             (if-not @system-prompt-cache
               (reset! system-prompt-cache system-prompt))
             (swap! prompt-cache assoc session-id prompt)
             (resolve prompt)))
         (catch :default error
           (reject error)))))))

(defn build-system-instructions
  "Constructs the comprehensive system instructions string using the prompt data and user context."
  [system-prompt-data]
  (let [user-name (-> system-prompt-data :user :name)
        user-email (-> system-prompt-data :user :email)
        concepts (or (-> system-prompt-data :user :concepts) [])
        chat-history (or (-> system-prompt-data :user :chat-history) [])
        name-opt-out (-> system-prompt-data :user :name-opt-out)
        email-opt-out (-> system-prompt-data :user :email-opt-out)
        opt-out (-> system-prompt-data :user :opt-out)]
    (str (:system-prompt system-prompt-data)
         "\n\n"
         (if user-name
           (str "The potential customer you are conversating with is"
                user-name " (do not ask for their name)"))
         (if (and user-name user-email)
           (str " and email is "
                user-email " (do not ask for their email)"))
         (if opt-out
           ". They've asked to delete all the data we have persisted. Tell them we have done so.")
         (if (and (not opt-out) user-name (not user-email) (not email-opt-out))
           (str " but we dont have an email yet. Ask nicely for their email and also indicate it's not mandatory."))
         (if (and (not opt-out)
                  user-name
                  (not user-email)
                  email-opt-out) ". Don't ask for their email unless they need to reach a human.")
         (if (and (not opt-out)
                  (not user-name)
                  user-email
                  (not name-opt-out))
           (str "The potential customer email is "
                user-email
                " and you do not have their name yet. Ask nicely for their"
                " name when it makes sense to do so. Stating the reason for asking"
                " for their name is to link it to the email. Also indicate this is not necessary."))
         (if (and (not opt-out)
                  (not user-name)
                  user-email
                  name-opt-out)
           (str "The potential customer email is "
                user-email " and you dont have their name yet."
                " Don't ask for the customer's name."))
         (if (and (not opt-out)
                  (not-empty concepts))
           (str "\n\nThe concept(s) the customer has mentioned include:\n"))
         (if (and (not opt-out)
                  (not-empty concepts))
           (->> concepts
                (map #(str "- " % ""))
                (string/join "\n")))
         (if (and (not opt-out)
                  (not-empty chat-history))
           (str "\n\nThe following is the history of the last 20 messages"
                " with the customer in ascending order (newest message is at the bottom of the list)."
                " Your name is '" BOT_NAME "' (CRITICAL Never disclose your name)."
                " There is only one other participant and their name may change but it is the same person:\n"))
         (if (and (not opt-out)
                  (not-empty chat-history))
           (string/join "\n" chat-history)))))

(defn update-chat-history!
  "Updates the chat history in the cache and Firestore, appending the new user input and bot response."
  [session-id user-input response-text opt-out chat-doc]
  (p/create
   (fn [resolve reject]
     (go
       (try
         (let [updated-chat-history
               (take-last
                20 (into (vec (or (get-in @prompt-cache [session-id :chat-history])
                                  (:chat-history chat-doc) []))
                         [(str (or (:name chat-doc)
                                   (:email chat-doc)
                                   session-id) ": " user-input)
                          (str BOT_NAME ": " response-text)]))]
           (swap! prompt-cache assoc-in [session-id :chat-history] updated-chat-history)
           (if opt-out
             (swap! prompt-cache utils/dissoc-in [session-id :user]))
           (<p! (firestore/upsert-doc "chats" session-id {:chat-history updated-chat-history}))
           (resolve nil))
         (catch :default e (reject e)))))))

(defn generate-response
  "Generates a response from the Gemini AI based on the user's input and session context."
  [session-id user-input]
  (p/create
   (fn [resolve reject]
     (go
       (try
         (let [system-prompt-data (<p! (get-system-prompt session-id AGENTS_MD_PATH))
               system-instructions (build-system-instructions system-prompt-data)
               ^GeminiAi gemini-ai @ai-client
               response (<p! (.generateContent
                              (.-models gemini-ai)
                              #js {:model "gemini-2.5-flash"
                                   :contents user-input
                                   :config
                                   #js {:systemInstruction
                                        (str system-instructions
                                             "\n\n Remember: EVERY reply must end with a bracket code, even if it is just `[]`.")
                                        :temperature 0.2}}))
               response-text (.-text response)
               chat-doc (<p! (firestore/get-doc "chats" session-id))
               opt-out (-> system-prompt-data :user :opt-out)]
           (<p! (update-chat-history! session-id user-input response-text opt-out chat-doc))
           (resolve response-text))
         (catch js/Error e
           (js/console.error "Gemini API Error:" e)
           (resolve "Sorry, I am experiencing technical difficulties. []")))))))

(defn ^:export chat
  "Cloud function entry point for handling chat requests."
  [^Request req res]
  (go
    (try
      (js/console.log "Running cloud function: chat")
      (let [input req.body.input
            session-id req.body.sid
            chat-response (<p! (generate-response session-id input))
            test-chat-response "I need to connect you with a human agent. []"
            matches (re-find pattern chat-response)
            reply (nth matches 1 nil)
            code (nth matches 2 nil)]
        (when code
          (<p! (handle-code session-id code)))
        (.send res (t/write t-writer {:reply reply})))
      (catch :default error
        (js/console.error error)
        (.send res (t/write t-writer {:error (.-message error)}))))))

(go
  (try
    (let [gemini-key (<p! (.readFile (.-promises fs) GEMINI_KEYS_PATH))
          key (js->clj (.parse js/JSON gemini-key) :keywordize-keys true)]
      (reset! ai-client
              (google-gen-ai.GoogleGenAI.
               #js {:apiKey (:key key)})))
    (catch :default e)))

(.http cloud-functions-framework "chat" chat)
