package com.maodouchat.explore.repository

class MutationSessionChangedException : IllegalStateException("Session changed during post mutation")
