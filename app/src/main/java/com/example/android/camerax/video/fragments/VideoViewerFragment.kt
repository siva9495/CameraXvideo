/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Simple VideoView to display the just captured video

package com.example.android.camerax.video.fragments

import android.app.ProgressDialog
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.CursorIndexOutOfBoundsException
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import androidx.navigation.fragment.navArgs
import com.example.android.camerax.video.databinding.FragmentVideoViewerBinding
import android.util.TypedValue
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import com.example.android.camerax.video.R
import kotlinx.coroutines.launch
import java.lang.RuntimeException
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.UploadTask
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/**
 * VideoViewerFragment:
 *      Accept MediaStore URI and play it with VideoView (Also displaying file size and location)
 *      Note: Might be good to retrieve the encoded file mime type (not based on file type)
 */
class VideoViewerFragment : androidx.fragment.app.Fragment() {
    private val args: VideoViewerFragmentArgs by navArgs()

    // This property is only valid between onCreateView and onDestroyView.
    private var _binding: FragmentVideoViewerBinding? = null
    private val binding get() = _binding!!

    private val storage = FirebaseStorage.getInstance()
    private val storageReference = storage.reference

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoViewerBinding.inflate(inflater, container, false)
        // UI adjustment + hacking to display VideoView use tips / capture result
        val tv = TypedValue()
        if (requireActivity().theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            val actionBarHeight = TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
            binding.videoViewerTips.y  = binding.videoViewerTips.y - actionBarHeight
        }

        return binding.root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            showVideo(args.uri)
        } else {
            // force MediaScanner to re-scan the media file.
            val path = getAbsolutePathFromUri(args.uri) ?: return
            MediaScannerConnection.scanFile(
                context, arrayOf(path), null
            ) { _, uri ->
                // playback video on main thread with VideoView
                if (uri != null) {
                    lifecycleScope.launch {
                        showVideo(uri)
                    }
                }
            }
        }

        // Handle back button press
        binding.backButton.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigateUp()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /**
     * A helper function to play the recorded video. Note that VideoView/MediaController auto-hides
     * the play control menus, touch on the video area would bring it back for 3 second.
     * This functionality not really related to capture, provided here for convenient purpose to view:
     *   - the captured video
     *   - the file size and location
     */
    private fun showVideo(uri : Uri) {
        val fileSize = getFileSizeFromUri(uri)
        if (fileSize == null || fileSize <= 0) {
            Log.e("VideoViewerFragment", "Failed to get recorded file size, could not be played!")
            return
        }

        val filePath = getAbsolutePathFromUri(uri) ?: return
        val fileInfo = "FileSize: $fileSize\n $filePath"
        Log.i("VideoViewerFragment", fileInfo)
        binding.videoViewerTips.text = fileInfo

        uploadToFirebaseStorage(uri)

        val mc = MediaController(requireContext())
        binding.videoViewer.apply {
            setVideoURI(uri)
            setMediaController(mc)
            requestFocus()
        }.start()
        mc.show(0)
    }

    private fun uploadToFirebaseStorage(uri: Uri) {
        val sharedPreferences = requireContext().getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        val isVideoUploaded = sharedPreferences.getBoolean("videoUploaded", false)

        if (isVideoUploaded) {
            Log.i("VideoViewerFragment", "Video has already been uploaded.")
            Toast.makeText(context, "Video has already been uploaded", Toast.LENGTH_SHORT).show()
            return
        }

        val progressDialog = ProgressDialog(requireContext())
        progressDialog.setTitle("Uploading Video")
        progressDialog.setMessage("Please wait while the video is being uploaded...")
        progressDialog.setCancelable(false)
        progressDialog.show()


        val file = File(getAbsolutePathFromUri(uri) ?: return)


        // Generate a file name with current date
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis())
        val fileName = "video_$timeStamp.mp4"
        val storageRef: StorageReference = storageReference.child("cameraVideos/$fileName")
        val uploadTask: UploadTask = storageRef.putFile(uri)

        uploadTask.addOnSuccessListener { taskSnapshot ->
            progressDialog.dismiss()
            Log.i("VideoViewerFragment", "Upload successful! ${taskSnapshot.metadata?.path}")
            Toast.makeText(context, "Uploaded successfully", Toast.LENGTH_SHORT).show()
            markVideoAsUploaded() // Mark the video as uploaded
            // Handle successful upload, you may want to show a success message or navigate to another screen
        }.addOnFailureListener {
            progressDialog.dismiss()
            Log.e("VideoViewerFragment", "Upload failed! ${it.message}")
            // Handle unsuccessful uploads, show an error message to the user
        }.addOnProgressListener { taskSnapshot ->
            // Update progress of the upload
            val progress = (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount).toInt()
            progressDialog.setMessage("Uploading: $progress%")
        }


    }

    private fun markVideoAsUploaded() {
        val sharedPreferences = requireContext().getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        sharedPreferences.edit().putBoolean("videoUploaded", true).apply()
    }



    /**
     * A helper function to get the captured file location.
     */
    private fun getAbsolutePathFromUri(contentUri: Uri): String? {
        var cursor:Cursor? = null
        return try {
            cursor = requireContext()
                .contentResolver
                .query(contentUri, arrayOf(MediaStore.Images.Media.DATA), null, null, null)
            if (cursor == null) {
                return null
            }
            val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            cursor.moveToFirst()
            cursor.getString(columnIndex)
        } catch (e: RuntimeException) {
            Log.e("VideoViewerFragment", String.format(
                "Failed in getting absolute path for Uri %s with Exception %s",
                contentUri.toString(), e.toString()
            )
            )
            null
        } finally {
            cursor?.close()
        }
    }

    /**
     * A helper function to retrieve the captured file size.
     */
    private fun getFileSizeFromUri(contentUri: Uri): Long? {
        val cursor = requireContext()
            .contentResolver
            .query(contentUri, null, null, null, null)
            ?: return null

        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        cursor.moveToFirst()

        cursor.use {
            return it.getLong(sizeIndex)
        }
    }


}
