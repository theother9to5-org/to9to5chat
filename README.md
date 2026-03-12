# theother9to5 Chatbot

This is the customer success and technical sales chatbot for **theother9to5**, implemented in ClojureScript and deployed as a Google Cloud Function running on Node.js.

## Overview

The chatbot acts as a knowledgeable guide for entrepreneurs and non-technical founders looking to build foundational, coding-agent-driven micro-SaaS products. It uses a specific system prompt (defined in `AGENTS.md`) to maintain a friendly, empathetic persona, explain the company's core philosophy and plans, and capture key user information.

## Technology Stack

- **Language**: ClojureScript
- **Environment**: Node.js via Google Cloud Functions Framework (`@google-cloud/functions-framework`)
- **AI Integration**: Google Gen AI (Gemini 2.5 Flash via `@google/genai`)
- **Database**: Google Cloud Firestore (`@google-cloud/firestore`)
- **Task Queue**: Google Cloud Tasks (`@google-cloud/tasks`)

## Core Functionality

1. **System Prompt Integration**: The chatbot utilizes the system prompt in `AGENTS.md`, which defines its persona, company philosophy, available plans (BASIC, PRO, ENTERPRISE), and FAQ response guidelines.
2. **Conversation Context**: The bot retrieves and builds context from Firestore, keeping track of the user's name, email, product concepts, and chat history.
3. **Data Extraction (Bracket Commands)**: The Gemini AI is strictly instructed to append specific command codes in square brackets at the end of every response (e.g., `[name:John]`, `[email:test@test.com]`, `[concept:a local bakery app]`).
4. **Data Handling**: The Cloud Function extracts these commands from the AI's response using regex and processes them:
   - **Name/Email**: Upserts the user's details to Firestore. If an email is provided for the first time, it queues a Cloud Task to send a follow-up email after a 7-minute delay.
   - **Concept**: Stores the user's product concepts (keeping up to the last 5) in Firestore.
   - **Opt-outs**: Handles user requests to opt-out of providing contact info or to delete all their chat data entirely.

## Entry Point

The main entry point for the Google Cloud Function is the `chat` function defined in `chat.cljs`. It handles the incoming HTTP request, generates a response using Gemini, parses any returned bracket commands, executes the corresponding side-effects (database/tasks updates), and sends the AI's reply back to the client.
