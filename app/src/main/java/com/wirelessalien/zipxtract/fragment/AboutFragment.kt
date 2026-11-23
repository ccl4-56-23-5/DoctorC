/*
 *  Copyright (C) 2023  WirelessAlien <https://github.com/WirelessAlien>
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.wirelessalien.zipxtract.fragment

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import com.wirelessalien.zipxtract.BuildConfig
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.databinding.FragmentAboutBinding


class AboutFragment : DialogFragment() {
    private lateinit var binding: FragmentAboutBinding
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.versionNumberText.text = "7Zip Android " + BuildConfig.VERSION_NAME + "\n基于 ZipXtract 7.1.1 的非官方衍生版\n7-Zip Android 绑定：16.02-2.03\nGPL-3.0-or-later · 源码随安装包提供"
        binding.sourceCode.text = "上游项目源码"
        binding.donate.visibility = View.GONE

        binding.sourceCode.setOnClickListener {
            openUrl("https://github.com/WirelessAlien/ZipXtract")
        }

        binding.reportIssue.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("本地衍生版").setMessage("请向本版开发者反馈。上游 ZipXtract 不负责本版修改。").setPositiveButton(android.R.string.ok, null).show()
        }

        binding.licenseText.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("GPL-3.0-or-later").setMessage(requireContext().assets.open("GPL-3.0.txt").bufferedReader().use { it.readText() }).setPositiveButton(android.R.string.ok, null).show()
        }

        binding.shareIcon.setOnClickListener {
            val shareUrl = "7Zip Android " + BuildConfig.VERSION_NAME + "，基于 ZipXtract 的非官方衍生版。安装包与源码请向提供本版的开发者获取。"
            val shareIntent = Intent(Intent.ACTION_SEND)
            shareIntent.type = "text/plain"
            shareIntent.putExtra(Intent.EXTRA_TEXT, shareUrl)
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
        }

        binding.privacyPolicyLink.setOnClickListener {
            openUrl("https://sites.google.com/view/privacy-policy-zipxtract/home")
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }
}
