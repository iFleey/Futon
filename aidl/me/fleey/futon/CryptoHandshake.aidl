/*
 * Futon - Android Automation Daemon Interface
 * Copyright (C) 2025 Fleey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package me.fleey.futon;

/**
 * Crypto handshake result for establishing encrypted channels
 */
parcelable CryptoHandshake {
    // Daemon's DH public key (X25519, 32 bytes)
    byte[] dhPublicKey;
    
    // Session ID for this encrypted session
    String sessionId;
    
    // Key generation number
    long keyGeneration;
    
    // Supported crypto features (bitmask)
    int capabilities;
    
    // Error code (0 = success)
    int errorCode;
    
    // Error message (if errorCode != 0)
    String errorMessage;
}
