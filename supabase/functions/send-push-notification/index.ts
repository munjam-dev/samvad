import { serve } from 'https://deno.land/std@0.168.0/http/server.ts'
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

// Edge function to send push notifications via Firebase Cloud Messaging (FCM)
// It grabs the user's push tokens from the 'push_tokens' table and calls FCM API.

serve(async (req) => {
  try {
    const { receiver_id, title, body, data } = await req.json()

    // 1. Initialize Supabase Admin Client to bypass RLS for grabbing tokens
    const supabaseUrl = Deno.env.get('SUPABASE_URL')
    const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')

    if (!supabaseUrl || !supabaseServiceKey) {
      throw new Error('Supabase environment variables missing')
    }

    const supabaseAdmin = createClient(supabaseUrl, supabaseServiceKey)

    // 2. Fetch user's push tokens
    const { data: tokens, error } = await supabaseAdmin
      .from('push_tokens')
      .select('token, platform')
      .eq('user_id', receiver_id)

    if (error || !tokens || tokens.length === 0) {
      return new Response(JSON.stringify({ message: 'No push tokens found for user' }), { status: 200 })
    }

    // 3. Send via FCM (HTTP v1 API using Google Service Account credentials)
    // For this example, we assume we have an FCM server key or access token
    const fcmServerKey = Deno.env.get('FCM_SERVER_KEY')

    if (!fcmServerKey) {
      throw new Error('FCM server key missing')
    }

    const promises = tokens.map(async (tokenData) => {
      const fcmPayload = {
        to: tokenData.token,
        notification: { title, body },
        data: data || {},
      }

      return fetch('https://fcm.googleapis.com/fcm/send', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': \key=\${fcmServerKey}\,
        },
        body: JSON.stringify(fcmPayload),
      })
    })

    await Promise.all(promises)

    return new Response(JSON.stringify({ success: true, message: 'Notifications sent' }), {
      headers: { 'Content-Type': 'application/json' },
      status: 200,
    })
  } catch (error) {
    return new Response(JSON.stringify({ error: error.message }), {
      headers: { 'Content-Type': 'application/json' },
      status: 400,
    })
  }
})
