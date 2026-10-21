package io.aaps.copilot.storage

import androidx.core.content.FileProvider

// Android registers providers by component name; separate authorities alone are insufficient.
class AppUpdateFileProvider : FileProvider()
class MealPhotoFileProvider : FileProvider()
class ClinicalPdfFileProvider : FileProvider()
