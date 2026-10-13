// Production Backend Server for Samvad Messenger
const express = require('express');
const http = require('http');
const socketIo = require('socket.io');
const cors = require('cors');
const mongoose = require('mongoose');
const jwt = require('jsonwebtoken');
const bcrypt = require('bcrypt');
const dotenv = require('dotenv');

// Load environment variables
dotenv.config();

const app = express();
const server = http.createServer(app);
const io = socketIo(server, {
  cors: {
    origin: "*",
    methods: ["GET", "POST"]
  }
});

// Middleware
app.use(cors());
app.use(express.json());

// MongoDB Connection
mongoose.connect(process.env.MONGODB_URI || 'mongodb+srv://samvad:samvad123@cluster0.mongodb.net/samvad?retryWrites=true&w=majority', {
  useNewUrlParser: true,
  useUnifiedTopology: true,
});

// Schemas
const userSchema = new mongoose.Schema({
  phone: { type: String, unique: true, required: true },
  name: { type: String, required: true },
  email: { type: String },
  avatar: { type: String },
  isOnline: { type: Boolean, default: false },
  lastSeen: { type: Date, default: Date.now },
  createdAt: { type: Date, default: Date.now }
});

const messageSchema = new mongoose.Schema({
  senderId: { type: mongoose.Schema.Types.ObjectId, ref: 'User', required: true },
  receiverId: { type: mongoose.Schema.Types.ObjectId, ref: 'User', required: true },
  content: { type: String, required: true },
  type: { type: String, enum: ['text', 'image', 'file', 'voice'], default: 'text' },
  status: { type: String, enum: ['sent', 'delivered', 'read'], default: 'sent' },
  timestamp: { type: Date, default: Date.now },
  edited: { type: Boolean, default: false },
  deleted: { type: Boolean, default: false }
});

const User = mongoose.model('User', userSchema);
const Message = mongoose.model('Message', messageSchema);

// JWT Secret
const JWT_SECRET = process.env.JWT_SECRET || 'samvad_messenger_super_secret_key_2024_production_ready';

// Socket.IO connection handling
const connectedUsers = new Map();

io.on('connection', (socket) => {
  console.log('User connected to Samvad:', socket.id);

  // User authentication
  socket.on('authenticate', async (token) => {
    try {
      const decoded = jwt.verify(token, JWT_SECRET);
      const user = await User.findById(decoded.userId);
      
      if (user) {
        socket.userId = user._id.toString();
        connectedUsers.set(user._id.toString(), socket.id);
        
        // Update user online status
        await User.findByIdAndUpdate(user._id, { 
          isOnline: true, 
          lastSeen: new Date() 
        });
        
        socket.emit('authenticated', { success: true, user });
        
        // Notify other users that this user is online
        socket.broadcast.emit('user_online', { userId: user._id });
      }
    } catch (error) {
      socket.emit('authentication_error', { error: 'Invalid token' });
    }
  });

  // Handle sending messages
  socket.on('send_message', async (data) => {
    try {
      const { receiverId, content, type = 'text' } = data;
      
      const message = new Message({
        senderId: socket.userId,
        receiverId,
        content,
        type
      });
      
      await message.save();
      await message.populate('senderId receiverId');
      
      // Send to receiver if online
      const receiverSocketId = connectedUsers.get(receiverId);
      if (receiverSocketId) {
        io.to(receiverSocketId).emit('new_message', message);
      }
      
      // Send confirmation to sender
      socket.emit('message_sent', message);
      
    } catch (error) {
      socket.emit('message_error', { error: 'Failed to send message' });
    }
  });

  // Handle typing indicators
  socket.on('typing_start', (data) => {
    const { receiverId } = data;
    const receiverSocketId = connectedUsers.get(receiverId);
    if (receiverSocketId) {
      io.to(receiverSocketId).emit('user_typing', { userId: socket.userId });
    }
  });

  socket.on('typing_stop', (data) => {
    const { receiverId } = data;
    const receiverSocketId = connectedUsers.get(receiverId);
    if (receiverSocketId) {
      io.to(receiverSocketId).emit('user_stop_typing', { userId: socket.userId });
    }
  });

  // Handle message status updates
  socket.on('mark_delivered', async (data) => {
    try {
      const { messageId } = data;
      await Message.findByIdAndUpdate(messageId, { status: 'delivered' });
      
      const message = await Message.findById(messageId).populate('senderId');
      const senderSocketId = connectedUsers.get(message.senderId._id.toString());
      if (senderSocketId) {
        io.to(senderSocketId).emit('message_delivered', { messageId });
      }
    } catch (error) {
      console.error('Error marking message as delivered:', error);
    }
  });

  socket.on('mark_read', async (data) => {
    try {
      const { messageId } = data;
      await Message.findByIdAndUpdate(messageId, { status: 'read' });
      
      const message = await Message.findById(messageId).populate('senderId');
      const senderSocketId = connectedUsers.get(message.senderId._id.toString());
      if (senderSocketId) {
        io.to(senderSocketId).emit('message_read', { messageId });
      }
    } catch (error) {
      console.error('Error marking message as read:', error);
    }
  });

  // Handle disconnection
  socket.on('disconnect', async () => {
    if (socket.userId) {
      connectedUsers.delete(socket.userId);
      
      // Update user offline status
      await User.findByIdAndUpdate(socket.userId, { 
        isOnline: false, 
        lastSeen: new Date() 
      });
      
      // Notify other users that this user is offline
      socket.broadcast.emit('user_offline', { userId: socket.userId });
    }
    console.log('User disconnected from Samvad:', socket.id);
  });
});

// REST API Routes
app.post('/api/auth/register', async (req, res) => {
  try {
    const { phone, name, email } = req.body;
    
    // Check if user already exists
    const existingUser = await User.findOne({ phone });
    if (existingUser) {
      return res.status(400).json({ error: 'User already exists' });
    }
    
    const user = new User({ phone, name, email });
    await user.save();
    
    const token = jwt.sign({ userId: user._id }, JWT_SECRET, { expiresIn: '30d' });
    
    res.status(201).json({ user, token });
  } catch (error) {
    res.status(500).json({ error: 'Registration failed' });
  }
});

app.post('/api/auth/login', async (req, res) => {
  try {
    const { phone } = req.body;
    
    const user = await User.findOne({ phone });
    if (!user) {
      return res.status(400).json({ error: 'User not found' });
    }
    
    const token = jwt.sign({ userId: user._id }, JWT_SECRET, { expiresIn: '30d' });
    
    res.json({ user, token });
  } catch (error) {
    res.status(500).json({ error: 'Login failed' });
  }
});

app.get('/api/users', async (req, res) => {
  try {
    const users = await User.find().select('-__v');
    res.json(users);
  } catch (error) {
    res.status(500).json({ error: 'Failed to fetch users' });
  }
});

app.get('/api/messages/:userId', async (req, res) => {
  try {
    const { userId } = req.params;
    const currentUserId = req.user?.id; // This would come from auth middleware
    
    const messages = await Message.find({
      $or: [
        { senderId: currentUserId, receiverId: userId },
        { senderId: userId, receiverId: currentUserId }
      ]
    }).populate('senderId receiverId').sort('timestamp');
    
    res.json(messages);
  } catch (error) {
    res.status(500).json({ error: 'Failed to fetch messages' });
  }
});

// Health check endpoint
app.get('/health', (req, res) => {
  res.json({ status: 'OK', message: 'Samvad Messenger API is running', timestamp: new Date().toISOString() });
});

const PORT = process.env.PORT || 5000;
server.listen(PORT, () => {
  console.log(`Samvad Messenger server running on port ${PORT}`);
});

module.exports = app;
