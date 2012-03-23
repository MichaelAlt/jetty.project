// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.ByteArrayISO8859Writer;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/** Response.
 * <p>
 * Implements {@link javax.servlet.http.HttpServletResponse} from the <code>javax.servlet.http</code> package.
 * </p>
 */
public class Response implements HttpServletResponse
{
    private static final Logger LOG = Log.getLogger(Response.class);

    public enum Output {NONE,STREAM,WRITER}

    /**
     * If a header name starts with this string,  the header (stripped of the prefix)
     * can be set during include using only {@link #setHeader(String, String)} or
     * {@link #addHeader(String, String)}.
     */
    public final static String SET_INCLUDE_HEADER_PREFIX = "org.eclipse.jetty.server.include.";

    /**
     * If this string is found within the comment of a cookie added with {@link #addCookie(Cookie)}, then the cookie 
     * will be set as HTTP ONLY.
     */
    public final static String HTTP_ONLY_COMMENT="__HTTP_ONLY__";
    
    private final HttpChannel _connection;
    private final HttpFields _fields;
    private int _status=SC_OK;
    private String _reason;
    private Locale _locale;
    private MimeTypes.Type _mimeType;
    private String _characterEncoding;
    private String _contentType;
    private Output _outputState;
    private PrintWriter _writer;
    private long _contentLength;

    
    
    /* ------------------------------------------------------------ */
    /**
     *
     */
    public Response(HttpChannel connection)
    {
        _connection=connection;
        _fields=connection.getResponseFields();
    }


    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#reset()
     */
    protected void recycle()
    {
        _status=SC_OK;
        _reason=null;
        _locale=null;
        _mimeType=null;
        _characterEncoding=null;
        _contentType=null;
        _writer=null;
        _outputState=Output.NONE;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#addCookie(javax.servlet.http.Cookie)
     */
    public void addCookie(HttpCookie cookie)
    {
        _fields.addSetCookie(cookie);
    }
    
    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#addCookie(javax.servlet.http.Cookie)
     */
    public void addCookie(Cookie cookie)
    {
        String comment=cookie.getComment();
        boolean http_only=false;
        
        if (comment!=null)
        {
            int i=comment.indexOf(HTTP_ONLY_COMMENT);
            if (i>=0)
            {
                http_only=true;
                comment=comment.substring(i,i+HTTP_ONLY_COMMENT.length()).trim();
                if (comment.length()==0)
                    comment=null;
            }
        }
        _fields.addSetCookie(cookie.getName(),
                cookie.getValue(),
                cookie.getDomain(),
                cookie.getPath(),
                cookie.getMaxAge(),
                comment,
                cookie.getSecure(),
                http_only || cookie.isHttpOnly(),
                cookie.getVersion());
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#containsHeader(java.lang.String)
     */
    public boolean containsHeader(String name)
    {
        return _fields.containsKey(name);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#encodeURL(java.lang.String)
     */
    public String encodeURL(String url)
    {
        final Request request=_connection.getRequest();
        SessionManager sessionManager = request.getSessionManager();
        if (sessionManager==null)
            return url;
        
        HttpURI uri = null;
        if (sessionManager.isCheckingRemoteSessionIdEncoding() && URIUtil.hasScheme(url))
        {
            uri = new HttpURI(url);
            String path = uri.getPath();
            path = (path == null?"":path);
            int port=uri.getPort();
            if (port<0) 
                port = HttpScheme.HTTPS.toString().equalsIgnoreCase(uri.getScheme())?443:80;
            if (!request.getServerName().equalsIgnoreCase(uri.getHost()) ||
                request.getServerPort()!=port ||
                !path.startsWith(request.getContextPath())) //TODO the root context path is "", with which every non null string starts
                return url;
        }
        
        String sessionURLPrefix = sessionManager.getSessionIdPathParameterNamePrefix();
        if (sessionURLPrefix==null)
            return url;

        if (url==null)
            return null;
        
        // should not encode if cookies in evidence
        if (request.isRequestedSessionIdFromCookie())
        {
            int prefix=url.indexOf(sessionURLPrefix);
            if (prefix!=-1)
            {
                int suffix=url.indexOf("?",prefix);
                if (suffix<0)
                    suffix=url.indexOf("#",prefix);

                if (suffix<=prefix)
                    return url.substring(0,prefix);
                return url.substring(0,prefix)+url.substring(suffix);
            }
            return url;
        }

        // get session;
        HttpSession session=request.getSession(false);

        // no session
        if (session == null)
            return url;

        // invalid session
        if (!sessionManager.isValid(session))
            return url;

        String id=sessionManager.getNodeId(session);

        if (uri == null)
                uri = new HttpURI(url);
     
        
        // Already encoded
        int prefix=url.indexOf(sessionURLPrefix);
        if (prefix!=-1)
        {
            int suffix=url.indexOf("?",prefix);
            if (suffix<0)
                suffix=url.indexOf("#",prefix);

            if (suffix<=prefix)
                return url.substring(0,prefix+sessionURLPrefix.length())+id;
            return url.substring(0,prefix+sessionURLPrefix.length())+id+
                url.substring(suffix);
        }

        // edit the session
        int suffix=url.indexOf('?');
        if (suffix<0)
            suffix=url.indexOf('#');
        if (suffix<0) 
        {          
            return url+ 
                   ((HttpScheme.HTTPS.is(uri.getScheme()) || HttpScheme.HTTP.is(uri.getScheme())) && uri.getPath()==null?"/":"") + //if no path, insert the root path
                   sessionURLPrefix+id;
        }
     
        
        return url.substring(0,suffix)+
            ((HttpScheme.HTTPS.is(uri.getScheme()) || HttpScheme.HTTP.is(uri.getScheme())) && uri.getPath()==null?"/":"")+ //if no path so insert the root path
            sessionURLPrefix+id+url.substring(suffix);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see javax.servlet.http.HttpServletResponse#encodeRedirectURL(java.lang.String)
     */
    public String encodeRedirectURL(String url)
    {
        return encodeURL(url);
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public String encodeUrl(String url)
    {
        return encodeURL(url);
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public String encodeRedirectUrl(String url)
    {
        return encodeRedirectURL(url);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#sendError(int, java.lang.String)
     */
    public void sendError(int code, String message) throws IOException
    {
    	if (_connection.isIncluding())
    		return;

        if (isCommitted())
            LOG.warn("Committed before "+code+" "+message);

        resetBuffer();
        _characterEncoding=null;
        setHeader(HttpHeader.EXPIRES,null);
        setHeader(HttpHeader.LAST_MODIFIED,null);
        setHeader(HttpHeader.CACHE_CONTROL,null);
        setHeader(HttpHeader.CONTENT_TYPE,null);
        setHeader(HttpHeader.CONTENT_LENGTH,null);

        _outputState=Output.NONE;
        setStatus(code,message);

        if (message==null)
            message=HttpStatus.getMessage(code);

        // If we are allowed to have a body
        if (code!=SC_NO_CONTENT &&
            code!=SC_NOT_MODIFIED &&
            code!=SC_PARTIAL_CONTENT &&
            code>=SC_OK)
        {
            Request request = _connection.getRequest();

            ErrorHandler error_handler = null;
            ContextHandler.Context context = request.getContext();
            if (context!=null)
                error_handler=context.getContextHandler().getErrorHandler();
            if (error_handler==null)
                error_handler = _connection.getServer().getBean(ErrorHandler.class);
            if (error_handler!=null)
            {
                request.setAttribute(Dispatcher.ERROR_STATUS_CODE,new Integer(code));
                request.setAttribute(Dispatcher.ERROR_MESSAGE, message);
                request.setAttribute(Dispatcher.ERROR_REQUEST_URI, request.getRequestURI());
                request.setAttribute(Dispatcher.ERROR_SERVLET_NAME,request.getServletName());
                error_handler.handle(null,_connection.getRequest(),_connection.getRequest(),this );
            }
            else
            {
                setHeader(HttpHeader.CACHE_CONTROL, "must-revalidate,no-cache,no-store");
                setContentType(MimeTypes.Type.TEXT_HTML_8859_1.toString());
                ByteArrayISO8859Writer writer= new ByteArrayISO8859Writer(2048);
                if (message != null)
                {
                    message= StringUtil.replace(message, "&", "&amp;");
                    message= StringUtil.replace(message, "<", "&lt;");
                    message= StringUtil.replace(message, ">", "&gt;");
                }
                String uri= request.getRequestURI();
                if (uri!=null)
                {
                    uri= StringUtil.replace(uri, "&", "&amp;");
                    uri= StringUtil.replace(uri, "<", "&lt;");
                    uri= StringUtil.replace(uri, ">", "&gt;");
                }

                writer.write("<html>\n<head>\n<meta http-equiv=\"Content-Type\" content=\"text/html;charset=ISO-8859-1\"/>\n");
                writer.write("<title>Error ");
                writer.write(Integer.toString(code));
                writer.write(' ');
                if (message==null)
                    message=HttpStatus.getMessage(code);
                writer.write(message);
                writer.write("</title>\n</head>\n<body>\n<h2>HTTP ERROR: ");
                writer.write(Integer.toString(code));
                writer.write("</h2>\n<p>Problem accessing ");
                writer.write(uri);
                writer.write(". Reason:\n<pre>    ");
                writer.write(message);
                writer.write("</pre>");
                writer.write("</p>\n<hr /><i><small>Powered by Jetty://</small></i>");

                for (int i= 0; i < 20; i++)
                    writer.write("\n                                                ");
                writer.write("\n</body>\n</html>\n");

                writer.flush();
                setContentLength(writer.size());
                writer.writeTo(getOutputStream());
                writer.destroy();
            }
        }
        else if (code!=SC_PARTIAL_CONTENT)
        {
            _connection.getRequestFields().remove(HttpHeader.CONTENT_TYPE);
            _connection.getRequestFields().remove(HttpHeader.CONTENT_LENGTH);
            _characterEncoding=null;
            _mimeType=null;
        }

        complete();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#sendError(int)
     */
    public void sendError(int sc) throws IOException
    {
        if (sc==102)
            sendProcessing();
        else
            sendError(sc,null);
    }

    /* ------------------------------------------------------------ */
    /* Send a 102-Processing response.
     * If the connection is a HTTP connection, the version is 1.1 and the
     * request has a Expect header starting with 102, then a 102 response is
     * sent. This indicates that the request still be processed and real response
     * can still be sent.   This method is called by sendError if it is passed 102.
     * @see javax.servlet.http.HttpServletResponse#sendError(int)
     */
    public void sendProcessing() throws IOException
    {
        if (_connection.isExpecting102Processing() && !isCommitted())
            _connection.send1xx(HttpStatus.PROCESSING_102);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#sendRedirect(java.lang.String)
     */
    public void sendRedirect(String location) throws IOException
    {
    	if (_connection.isIncluding())
    		return;

        if (location==null)
            throw new IllegalArgumentException();

        if (!URIUtil.hasScheme(location))
        {
            StringBuilder buf = _connection.getRequest().getRootURL();
            if (location.startsWith("/"))
                buf.append(location);
            else
            {
                String path=_connection.getRequest().getRequestURI();
                String parent=(path.endsWith("/"))?path:URIUtil.parentPath(path);
                location=URIUtil.addPaths(parent,location);
                if(location==null)
                    throw new IllegalStateException("path cannot be above root");
                if (!location.startsWith("/"))
                    buf.append('/');
                buf.append(location);
            }

            location=buf.toString();
            HttpURI uri = new HttpURI(location);
            String path=uri.getDecodedPath();
            String canonical=URIUtil.canonicalPath(path);
            if (canonical==null)
                throw new IllegalArgumentException();
            if (!canonical.equals(path))
            {
                buf = _connection.getRequest().getRootURL();
                buf.append(URIUtil.encodePath(canonical));
                if (uri.getQuery()!=null)
                {
                    buf.append('?');
                    buf.append(uri.getQuery());
                }
                if (uri.getFragment()!=null)
                {
                    buf.append('#');
                    buf.append(uri.getFragment());
                }
                location=buf.toString();
            }
        }
        
        resetBuffer();
        setHeader(HttpHeader.LOCATION,location);
        setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY);
        complete();

    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#setDateHeader(java.lang.String, long)
     */
    public void setDateHeader(String name, long date)
    {
        if (!_connection.isIncluding())
            _fields.putDateField(name, date);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#addDateHeader(java.lang.String, long)
     */
    public void addDateHeader(String name, long date)
    {
        if (!_connection.isIncluding())
            _fields.addDateField(name, date);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#setHeader(java.lang.String, java.lang.String)
     */
    public void setHeader(HttpHeader name, String value)
    {
        if (HttpHeader.CONTENT_TYPE == name)
            setContentType(value);
        else
        {
            if (_connection.isIncluding())
                    return;
            
            _fields.put(name, value);
            
            if (HttpHeader.CONTENT_LENGTH==name)
            {
                if (value==null)
                    _contentLength=-1l;
                else
                    _contentLength=Long.parseLong(value);
            }
        }
    }
    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#setHeader(java.lang.String, java.lang.String)
     */
    public void setHeader(String name, String value)
    {
        if (HttpHeader.CONTENT_TYPE.is(name))
            setContentType(value);
        else
        {
            if (_connection.isIncluding())
            {
                if (name.startsWith(SET_INCLUDE_HEADER_PREFIX))
                    name=name.substring(SET_INCLUDE_HEADER_PREFIX.length());
                else
                    return;
            }
            _fields.put(name, value);
            if (HttpHeader.CONTENT_LENGTH.is(name))
            {
                if (value==null)
                    _contentLength=-1l;
                else
                    _contentLength=Long.parseLong(value);
            }
        }
    }


    /* ------------------------------------------------------------ */
    public Collection<String> getHeaderNames()
    {
        final HttpFields fields=_fields;
        return fields.getFieldNamesCollection();
    }
    
    /* ------------------------------------------------------------ */
    /*
     */
    public String getHeader(String name)
    {
        return _fields.getStringField(name);
    }

    /* ------------------------------------------------------------ */
    /*
     */
    public Collection<String> getHeaders(String name)
    {
        final HttpFields fields=_fields;
        Collection<String> i = fields.getValuesCollection(name);
        if (i==null)
            return Collections.EMPTY_LIST;
        return i;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#addHeader(java.lang.String, java.lang.String)
     */
    public void addHeader(String name, String value)
    {
        if (_connection.isIncluding())
        {
            if (name.startsWith(SET_INCLUDE_HEADER_PREFIX))
                name=name.substring(SET_INCLUDE_HEADER_PREFIX.length());
            else
                return;
        }

        _fields.add(name, value);
        if (HttpHeader.CONTENT_LENGTH.is(name))
        {
            if (value==null)
                _contentLength=-1l;
            else
                _contentLength=Long.parseLong(value);
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#setIntHeader(java.lang.String, int)
     */
    public void setIntHeader(String name, int value)
    {
        if (!_connection.isIncluding())
        {
            _fields.putLongField(name, value);
            if (HttpHeader.CONTENT_LENGTH.is(name))
                _contentLength=value;
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#addIntHeader(java.lang.String, int)
     */
    public void addIntHeader(String name, int value)
    {
        if (!_connection.isIncluding())
        {
            _fields.add(name, Integer.toString(value));
            if (HttpHeader.CONTENT_LENGTH.is(name))
                _contentLength=value;
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#setStatus(int)
     */
    public void setStatus(int sc)
    {
        setStatus(sc,null);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#setStatus(int, java.lang.String)
     */
    public void setStatus(int sc, String sm)
    {
        if (sc<=0)
            throw new IllegalArgumentException();
        if (!_connection.isIncluding())
        {
            _status=sc;
            _reason=sm;
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#getCharacterEncoding()
     */
    public String getCharacterEncoding()
    {
        if (_characterEncoding==null)
            _characterEncoding=StringUtil.__ISO_8859_1;
        return _characterEncoding;
    }
    
    /* ------------------------------------------------------------ */
    String getSetCharacterEncoding()
    {
        return _characterEncoding;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#getContentType()
     */
    public String getContentType()
    {
        return _contentType;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#getOutputStream()
     */
    public ServletOutputStream getOutputStream() throws IOException
    {
        if (_outputState==Output.WRITER)
            throw new IllegalStateException("WRITER");

        ServletOutputStream out = _connection.getOutputStream();
        _outputState=Output.STREAM;
        return out;
    }

    /* ------------------------------------------------------------ */
    public boolean isWriting()
    {
        return _outputState==Output.WRITER;
    }

    /* ------------------------------------------------------------ */
    public boolean isOutputing()
    {
        return _outputState!=Output.NONE;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#getWriter()
     */
    public PrintWriter getWriter() throws IOException
    {
        if (_outputState==Output.STREAM)
            throw new IllegalStateException("STREAM");

        /* if there is no writer yet */
        if (_writer==null)
        {
            /* get encoding from Content-Type header */
            String encoding = _characterEncoding;

            if (encoding==null)
            {
                if (encoding==null)
                    encoding = StringUtil.__ISO_8859_1;

                setCharacterEncoding(encoding);
            }

            /* construct Writer using correct encoding */
            _writer = _connection.getPrintWriter(encoding);
        }
        _outputState=Output.WRITER;
        return _writer;
    }


    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#setContentLength(int)
     */
    public void setContentLength(int len)
    {
        // Protect from setting after committed as default handling
        // of a servlet HEAD request ALWAYS sets _content length, even
        // if the getHandling committed the response!
        if (isCommitted() || _connection.isIncluding())
            return;
        _contentLength=len;
        if (len>=0)
        {
            _fields.putLongField(HttpHeader.CONTENT_LENGTH.toString(), (long)len);
            if (_connection.isAllContentWritten())
            {
                switch(_outputState)
                {
                    case WRITER:
                        _writer.close();
                        break;
                    case STREAM:
                        try
                        {
                            getOutputStream().close();
                        }
                        catch(IOException e)
                        {
                            throw new RuntimeException(e);
                        }
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    public long getLongContentLength()
    {
        return _contentLength;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#setContentLength(int)
     */
    public void setLongContentLength(long len)
    {
        // Protect from setting after committed as default handling
        // of a servlet HEAD request ALWAYS sets _content length, even
        // if the getHandling committed the response!
        if (isCommitted() || _connection.isIncluding())
        	return;
        _contentLength=len;
        _fields.putLongField(HttpHeader.CONTENT_LENGTH.toString(), len);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#setCharacterEncoding(java.lang.String)
     */
    public void setCharacterEncoding(String encoding)
    {
        if (_connection.isIncluding())
                return;

        if (_outputState==Output.NONE && !isCommitted())
        {
            if (encoding==null)
            {
                // Clear any encoding.
                if (_characterEncoding!=null)
                {
                    _characterEncoding=null;
                    if (_contentType!=null)
                    {
                        _contentType=MimeTypes.getContentTypeWithoutCharset(_contentType);
                        _fields.put(HttpHeader.CONTENT_TYPE,_contentType);
                    }
                }
            }
            else
            {
                // No, so just add this one to the mimetype
                _characterEncoding=encoding;
                if (_contentType!=null)
                {
                    _contentType=MimeTypes.getContentTypeWithoutCharset(_contentType)+";charset="+encoding;
                    _fields.put(HttpHeader.CONTENT_TYPE,_contentType);
                }
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#setContentType(java.lang.String)
     */
    public void setContentType(String contentType)
    {
        if (isCommitted() || _connection.isIncluding())
            return;

        if (contentType==null)
        {
            if (_locale==null)
                _characterEncoding=null;
            _mimeType=null;
            _contentType=null;
            _fields.remove(HttpHeader.CONTENT_TYPE);
        }
        else
        {
            _contentType=contentType;
            _mimeType=MimeTypes.CACHE.get(contentType);
            String charset=_mimeType==null?MimeTypes.getCharsetFromContentType(contentType):_mimeType.getCharset().toString();
            
            if (charset!=null)
                _characterEncoding=charset;
            else if (_characterEncoding!=null)
            {
                _contentType=contentType+";charset="+_characterEncoding;
                _mimeType=null;
            }
            
            _fields.put(HttpHeader.CONTENT_TYPE,_contentType);
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#setBufferSize(int)
     */
    public void setBufferSize(int size)
    {
        if (isCommitted())
            throw new IllegalStateException("Committed or content written");
        _connection.increaseContentBufferSize(size);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#getBufferSize()
     */
    public int getBufferSize()
    {
        return _connection.getContentBufferSize();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#flushBuffer()
     */
    public void flushBuffer() throws IOException
    {
        _connection.flushResponse();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#reset()
     */
    public void reset()
    {
        resetBuffer();
        fwdReset();
        _status=200;
        _reason=null;
        
        HttpFields response_fields=_fields;
        
        response_fields.clear();
        String connection=_connection.getRequestFields().getStringField(HttpHeader.CONNECTION);
        if (connection!=null)
        {
            String[] values = connection.split(",");
            for  (int i=0;values!=null && i<values.length;i++)
            {
                HttpHeaderValue cb = HttpHeaderValue.CACHE.get(values[0].trim());

                if (cb!=null)
                {
                    switch(cb)
                    {
                        case CLOSE:
                            response_fields.put(HttpHeader.CONNECTION,HttpHeaderValue.CLOSE.toString());
                            break;

                        case KEEP_ALIVE:
                            if (HttpVersion.HTTP_1_0.is(_connection.getRequest().getProtocol()))
                                response_fields.put(HttpHeader.CONNECTION,HttpHeaderValue.KEEP_ALIVE.toString());
                            break;
                        case TE:
                            response_fields.put(HttpHeader.CONNECTION,HttpHeaderValue.TE.toString());
                            break;
                    }
                }
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#reset()
     */
    public void fwdReset()
    {
        resetBuffer();

        _writer=null;
        _outputState=Output.NONE;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#resetBuffer()
     */
    public void resetBuffer()
    {
        if (isCommitted())
            throw new IllegalStateException("Committed");
        _connection.resetBuffer();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#isCommitted()
     */
    public boolean isCommitted()
    {
        return _connection.isResponseCommitted();
    }


    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#setLocale(java.util.Locale)
     */
    public void setLocale(Locale locale)
    {
        if (locale == null || isCommitted() ||_connection.isIncluding())
            return;

        _locale = locale;
        _fields.put(HttpHeader.CONTENT_LANGUAGE,locale.toString().replace('_','-'));

        if (_outputState!=Output.NONE )
            return;

        if (_connection.getRequest().getContext()==null)
            return;

        String charset = _connection.getRequest().getContext().getContextHandler().getLocaleEncoding(locale);

        if (charset!=null && charset.length()>0 && _characterEncoding==null)
            setCharacterEncoding(charset);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#getLocale()
     */
    public Locale getLocale()
    {
        if (_locale==null)
            return Locale.getDefault();
        return _locale;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The HTTP status code that has been set for this request. This will be <code>200<code>
     *    ({@link HttpServletResponse#SC_OK}), unless explicitly set through one of the <code>setStatus</code> methods.
     */
    public int getStatus()
    {
        return _status;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The reason associated with the current {@link #getStatus() status}. This will be <code>null</code>,
     *    unless one of the <code>setStatus</code> methods have been called.
     */
    public String getReason()
    {
        return _reason;
    }

    /* ------------------------------------------------------------ */
    /**
     */
    public void complete()
        throws IOException
    {
        _connection.completeResponse();
    }

    /* ------------------------------------------------------------ */
    public HttpFields getHttpFields()
    {
        return _fields;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return "HTTP/1.1 "+_status+" "+ (_reason==null?"":_reason) +System.getProperty("line.separator")+
        _fields.toString();
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private static class NullOutput extends ServletOutputStream
    {
        @Override
        public void write(int b) throws IOException
        {
        }

        @Override
        public void print(String s) throws IOException
        {
        }

        @Override
        public void println(String s) throws IOException
        {
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException
        {
        }

    }
}
