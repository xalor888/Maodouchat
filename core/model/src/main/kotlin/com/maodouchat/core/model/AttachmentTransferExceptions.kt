package com.maodouchat.core.model

class AttachmentTooLargeException : IllegalStateException("attachment_too_large")

class AttachmentReferenceInvalidException : IllegalStateException("attachment_reference_invalid")
