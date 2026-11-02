package ir.salisec.autosender.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.salisec.autosender.App

@Composable
fun HomeScreen(app: App, go: (Screen) -> Unit) {
    ScreenScaffold {
        Title("SaliBussiness")
        SubTitle("فروش گزارش‌های امنیتی SaliSecure با ایمیل شخصی‌سازی‌شده")
        HomeCard("کمپین جدید", "ورود دامنه‌ها ← مخاطبان ← گزارش‌ها ← تولید متن", Icons.Default.Send) { app.session.reset(); go(Screen.Domains) }
        HomeCard("صندوق ورودی", "خواندن و پاسخ به ایمیل‌ها", Icons.Default.Email) { go(Screen.Inbox) }
        HomeCard("خط لوله فروش", "وضعیت سرنخ‌ها و پیگیری‌ها", Icons.Default.TrendingUp) { go(Screen.Pipeline) }
        HomeCard("ارسال و لاگ", "پیشرفت صف ارسال و گزارش زنده", Icons.Default.PlayArrow) { go(Screen.Send) }
        HomeCard("تنظیمات", "کلیدها، ارسال، اکانت ایمیل", Icons.Default.Settings) { go(Screen.Settings) }
    }
}

@Composable
private fun HomeCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontSize = 17.sp)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}
