// IslandRoot veya genişletilmiş ada çizim fonksiyonu içinde IslandContent.Call eşleşmesi:
is IslandContent.Call -> {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Gray),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = call.callerName.take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = call.callerName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = if (call.isRinging) "Gelen arama..." else "Görüşmede (${call.callDurationSeconds}s)",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Kırmızı Reddet Butonu
            IconButton(
                onClick = { IslandBus.sendCall(CallCommand.Reject) },
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF3B30))
            ) {
                Icon(
                    imageVector = Icons.Default.CallEnd,
                    contentDescription = "Reddet",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            // Yeşil Yanıtla Butonu
            IconButton(
                onClick = { IslandBus.sendCall(CallCommand.Answer) },
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF34C759))
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Kabul Et",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
