package com.example.connectapp.ui.bluetooth

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.connectapp.data.models.BluetoothDeviceItem
import com.example.connectapp.databinding.ItemBluetoothDeviceBinding

class BluetoothDeviceAdapter(
    private val onClick: (BluetoothDeviceItem) -> Unit
) : ListAdapter<BluetoothDeviceItem, BluetoothDeviceAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemBluetoothDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BluetoothDeviceItem) {
            binding.tvName.text = item.name
            binding.tvAddress.text = item.address
            binding.tvBonded.text = if (item.bonded) "Paired" else "Discovered"
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return VH(ItemBluetoothDeviceBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<BluetoothDeviceItem>() {
            override fun areItemsTheSame(a: BluetoothDeviceItem, b: BluetoothDeviceItem) =
                a.address == b.address
            override fun areContentsTheSame(a: BluetoothDeviceItem, b: BluetoothDeviceItem) =
                a == b
        }
    }
}
