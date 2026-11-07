package com.maodouchat.core.crypto

class SignalStorePersistenceException(cause: Throwable) :
    java.io.IOException("signal_store_persistence_failed", cause)
